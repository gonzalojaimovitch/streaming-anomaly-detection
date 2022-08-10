package es.dmr.uimp.realtime

import org.apache.commons.lang3.StringUtils
import org.apache.spark.SparkConf
import org.apache.spark.SparkContext
import org.apache.spark.mllib.clustering._
import org.apache.spark.streaming._
import org.apache.spark.streaming.dstream.DStream
import org.apache.spark.streaming.kafka.KafkaUtils
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerConfig, ProducerRecord}
import java.util.HashMap


import scala.math._
import com.univocity.parsers.csv.{CsvParser, CsvParserSettings}
import es.dmr.uimp.clustering.KMeansClusterInvoices
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.mllib.linalg.{Vector, Vectors}
import org.apache.spark.rdd.RDD
import es.dmr.uimp.clustering.Clustering._
import org.joda.time.DateTime

object InvoicePipeline {

  case class Purchase(invoiceNo: String, productID: String, quantity: Int, invoiceDate: String,
                      unitPrice: Double, customerID: String, country: String)

  case class Invoice(invoiceNo: String, avgUnitPrice: Double,
                     minUnitPrice: Double, maxUnitPrice: Double, time: Double,
                     numberItems: Double, lastUpdated : Long, lines: Int, customerId: String)


  def main(args: Array[String]) {

    val Array(modelFile, thresholdFile, modelFileBisect, thresholdFileBisect, zkQuorum, group, topics, numThreads, brokers) = args
    val sparkConf = new SparkConf().setAppName("InvoicePipeline")
    val sc = new SparkContext(sparkConf)
    val ssc = new StreamingContext(sc, Seconds(20))


    ssc.sparkContext.setLogLevel("ERROR")
    // Checkpointing
    ssc.checkpoint("./checkpoint")

    // Load model and broadcast
    val kmeans_tuple = loadKMeansAndThreshold(sc, modelFile, thresholdFile)
    val kmeans_model = sc.broadcast(kmeans_tuple._1)
    val kmeans_threshold = kmeans_tuple._2

    val bisectionkmeans_tuple = loadBisectingKMeansAndThreshold(sc, modelFileBisect, thresholdFileBisect)
    val bisectionkmeans_model = sc.broadcast(bisectionkmeans_tuple._1)
    val bisectionkmeans_threshold = bisectionkmeans_tuple._2

    // Broadcast brokers
    val brokers_bc = sc.broadcast(brokers)


    // Build pipeline

    // Connect to kafka. First we receive the data from the topic "purchases"
    val purchasesFeed = connectToTopics(ssc, zkQuorum, group, topics, numThreads)

    // Get the data from the topic "purchases" and map into tuple (invoice_id, Purchase)
    val purchases = purchasesFeed.map(x => (x._1,x._2))

    // The cancelled purchases are filtered
    val notCancelledPurchases = purchases.filter(x => !x._1.startsWith("C")).map(x => (x._1, stringToPurchase(x._2)))

    /**
     * Process each not-cancelled purchase, aggregating the values in an object invoice within the state.
     * The timeout is set to the SLA of 40 seconds. Therefore, when no purchase of the same invoice arrives in 40 seconds, the state is timed out.
     * If an invalid purchase arrives, the first element of the state is set to false, which controls which invoices will be considered as
     * invalid (those which have at least one invalid purchase). A list of the purchases is maintained in the status, so all the purchases of the invalid
     * invoices can be posted in the given topic
     */

    def processPurchase(key: String, value: Option[Purchase],
                        state: State[(Boolean, Option[Invoice], List[Purchase])]) : Option[(Boolean, Option[Invoice], List[Purchase])] = {

      def processInvoice(newPurchase : Purchase) = {

        // Check if the state has been created before
        if (state.exists){
          // If the state is created, check the boolean value to determine if the invoice is or not valid
          val isValid = state.get._1
          if (isValid) {
            // Check if the purchase is invalid
            if (isValidPurchase(newPurchase)) {
              // Calculate the update of the invoice based on the new purchase
              val invoiceLastVersion = state.get._2.get
              val newLines = invoiceLastVersion.lines + 1
              val newAvgPrice = (invoiceLastVersion.avgUnitPrice * invoiceLastVersion.lines + newPurchase.unitPrice) / newLines
              val newMaxPrice = max(invoiceLastVersion.maxUnitPrice, newPurchase.unitPrice)
              val newMinPrice = min(invoiceLastVersion.minUnitPrice, newPurchase.unitPrice)
              val newNumItems = invoiceLastVersion.numberItems + newPurchase.quantity

              val updatedInvoice = Invoice(invoiceLastVersion.invoiceNo, newAvgPrice, newMaxPrice, newMinPrice, invoiceLastVersion.time,
                newNumItems, System.currentTimeMillis(), newLines, invoiceLastVersion.customerId)

              state.update((true, Some(updatedInvoice), state.get._3 :+ newPurchase))
            }
            // When the purchase is invalid, but the invoice was considered valid based on other previous entries of purchases.
            else {
              // The first element is changes to false, the invoice object set to None, and the purchase is added to the list of purchases
              state.update((false, None, state.get._3 :+ newPurchase))
            }
          }
          // When the invoice has already been classified as invalid
          else {
            state.update((false, None, state.get._3 :+ newPurchase))
          }
        }
        // When this is the first purchase and the state hasn't been created yet
        else {
          if (isValidPurchase(newPurchase)){
            val newInvoice = Invoice(invoiceNo=newPurchase.invoiceNo, avgUnitPrice=0,
              minUnitPrice=0, maxUnitPrice=0, time=getHour(newPurchase.invoiceDate),
              numberItems=0, lastUpdated=System.currentTimeMillis(), lines=0, customerId=newPurchase.customerID)

            state.update((false, Some(newInvoice), List(newPurchase)))
          }
          else{
            // When the first purchase for the invoice is invalid
            state.update((false, None, List(newPurchase)))
          }
        }
        None
      }
      // When there is a new purchase (and the invoice is not timed out), the invoice is updated
      // When the invoice is timed out (value is empty), the invoice is returned and progress to the next step
      value match {
        case Some(newPurchase) => processInvoice(newPurchase)
        case _ if state.isTimingOut() => state.getOption()
      }
    }

    val stateSpec = StateSpec.function(processPurchase _).timeout(Seconds(40))


    // We stay with those states that have timed out (with the attribute isDefined)
    val timedoutInvoices = notCancelledPurchases.mapWithState(stateSpec).filter(x => x.isDefined).map(_.get)


    // Invalid purchases (those which have the first element of the state as false)
    // The third object in the tuple is the list with all the purchases
    // The list of purchases is mapped into a key value tuple to send
    val invalidInvoices = timedoutInvoices.filter(x => !x._1).map(_._3).flatMap(x => x).map(x => (x.invoiceNo,
      s"${x.invoiceNo}, ${x.invoiceDate}, ${x.productID}, ${x.quantity}, ${x.unitPrice}, ${x.customerID}, ${x.country}"))


    // Publish invalid purchases to Kafka topic "invalid_invoices"
    invalidInvoices.foreachRDD { rdd =>
      publishToKafka("invalid_invoices")(brokers_bc)(rdd)
    }

    // Correct invoices are those which have the first element of the status as true
    val correctInvoices = timedoutInvoices.filter(x => x._1).map(x => x._2.get)

    // Filter those invoices classified as anomaly by the kmeans model and the threshold
    val kMeansAnomalies = correctInvoices.filter(x => isAnomalyKMeans(x, kmeans_model.value, kmeans_threshold))
      .map(x => (x.invoiceNo,
        s"${x.invoiceNo}, ${x.lines}, ${x.numberItems}, ${x.minUnitPrice}, ${x.maxUnitPrice}, ${x.avgUnitPrice}, ${x.time}, ${x.customerId}"))

    kMeansAnomalies.foreachRDD { rdd =>
      publishToKafka("anomalies_kmeans")(brokers_bc)(rdd)
    }

    // Filter those invoices classified as anomaly by the bisectingkmeans model and the threshold
    val bisectingKMeansAnomalies = correctInvoices.filter(x => isAnomalyBisectingKMeans(x, bisectionkmeans_model.value, bisectionkmeans_threshold))
      .map(x => (x.invoiceNo,
        s"${x.invoiceNo}, ${x.lines}, ${x.numberItems}, ${x.minUnitPrice}, ${x.maxUnitPrice}, ${x.avgUnitPrice}, ${x.time}, ${x.customerId}"))

    bisectingKMeansAnomalies.foreachRDD { rdd =>
      publishToKafka("anomalies_kmeans_bisect")(brokers_bc)(rdd)
    }



    /**
     * Get the total number of cancelled invoices in the last 8 minutes, updating every minute, and publish to Kafka topic "cancelations_ma"
     */

    // Cancelled invoices are those whose invoiceID starts with "C"
    val cancelled = purchases.filter(x => x._1.startsWith("C")).map(x => (x._1, 1))

    val addCount = (x: Int, y: Int) => x + y
    val removeCount = (x: Int, y: Int) => x - y
    val filterEmpty = (x: (String, Int)) => x._2 != 0

    // The keys that have a value greater than 0 (the ones that are not filtered) will be considered as the cancelled invoices in the window
    // The result will be mapped to 1, so we can count only invoices rather than purchases
    val cancelledCountStream =
        cancelled.reduceByKeyAndWindow(addCount, removeCount, Seconds(480),
          Seconds(60), 2, filterEmpty).map(x => 1).reduce(_ + _).map(x => ("cancellations", x.toString))

    cancelledCountStream.foreachRDD { rdd =>
        publishToKafka("cancelations_ma")(brokers_bc)(rdd)
      }
    
    ssc.start() // Start the computation
    ssc.awaitTermination()

    }





    def publishToKafka(topic: String)(kafkaBrokers: Broadcast[String])(rdd: RDD[(String, String)]) = {
      rdd.foreachPartition(partition => {
        val producer = new KafkaProducer[String, String](kafkaConf(kafkaBrokers.value))
        partition.foreach(record => {
          producer.send(new ProducerRecord[String, String](topic, record._1, record._2.toString))
        })
        producer.close()
      })
    }

    def kafkaConf(brokers: String) = {
      val props = new HashMap[String, Object]()
      props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers)
      props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer")
      props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer")
      props
    }


    /**
     * Load the model information: centroid and threshold
     */

    def loadKMeansAndThreshold(sc: SparkContext, modelFile: String, thresholdFile: String): Tuple2[KMeansModel, Double] = {
      val kmeans = KMeansModel.load(sc, modelFile)
      // parse threshold file
      val rawData = sc.textFile(thresholdFile, 20)
      val threshold = rawData.map { line => line.toDouble }.first()

      (kmeans, threshold)
    }

    def loadBisectingKMeansAndThreshold(sc: SparkContext, modelFileBisect: String, thresholdFileBisect: String): Tuple2[BisectingKMeansModel, Double] = {
      val bisectingkmeans = BisectingKMeansModel.load(sc, modelFileBisect)
      // parse threshold file
      val rawData = sc.textFile(thresholdFileBisect, 20)
      val threshold = rawData.map { line => line.toDouble }.first()

      (bisectingkmeans, threshold)
    }


    def connectToTopics(ssc: StreamingContext, zkQuorum: String, group: String,
                           topics: String, numThreads: String): DStream[(String, String)] = {

      ssc.checkpoint("checkpoint")
      val topicMap = topics.split(",").map((_, numThreads.toInt)).toMap
      KafkaUtils.createStream(ssc, zkQuorum, group, topicMap)
    }

    /**
     * Calculate the distance of a data point to the centroid
     */

    def distToCentroidKMeans(datum: Vector, model: KMeansModel): Double = {
      val centroid = model.clusterCenters(model.predict(datum))
      Vectors.sqdist(datum, centroid)
    }

    def distToCentroidBisectingKMeans(datum: Vector, model: BisectingKMeansModel): Double = {
      val centroid = model.clusterCenters(model.predict(datum))
      Vectors.sqdist(datum, centroid)
    }

    /**
     * Determine if an invoice is an anomaly based on the distance to the centroid and the value of the threshold
     */

    def isAnomalyKMeans(invoice: Invoice, model: KMeansModel, threshold: Double): Boolean = {
      val datum = Vectors.dense(invoice.avgUnitPrice, invoice.minUnitPrice, invoice.maxUnitPrice, invoice.time, invoice.numberItems)
      val distance = distToCentroidKMeans(datum, model)
      distance > threshold
    }

    def isAnomalyBisectingKMeans(invoice: Invoice, model: BisectingKMeansModel, threshold: Double): Boolean = {
    val datum = Vectors.dense(invoice.avgUnitPrice, invoice.minUnitPrice, invoice.maxUnitPrice, invoice.time, invoice.numberItems)
    val distance = distToCentroidBisectingKMeans(datum, model)
    distance > threshold
    }

    /**
     * Get the hour out of date string. If the date is empty, return -1.0
     */
    def getHour(date: String): Double = {
        var out = -1.0
        if (!StringUtils.isEmpty(date)) {
          val hour = date.substring(10).split(":")(0)
          if (!StringUtils.isEmpty(hour))
            out = hour.trim.toDouble
        }
        out
      }

    /**
     * Convert a string to a Purchase object
     */
    def stringToPurchase(strPurchase: String): Purchase = {
      val arrayPurchase = strPurchase.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", -1)
      val purchase = Purchase(invoiceNo = arrayPurchase(0), productID = arrayPurchase(1), quantity = strToIntNoneZero(arrayPurchase(3)), invoiceDate = arrayPurchase(4),
          unitPrice = strToDoubleNoneZero(arrayPurchase(5)), customerID = arrayPurchase(6), country = arrayPurchase(7))
      purchase
    }

    /**
     * Int to string, considering the case it is empty, for which the Int will be 0
     */
    def strToIntNoneZero(string: String): Int = {
      (string.take(1), string) match {
        case ("-", string) => string.toInt
        case ("", string) => 0
        case _ => string.toInt
      }
    }

    /**
     * Double to string, considering the case it is empty, for which the Double will be 0
     */
    def strToDoubleNoneZero(string: String): Double = {
      (string.take(1), string) match {
        case ("-", string) => string.toDouble
        case ("", string) => 0
        case _ => string.toDouble
      }
    }

    /**
     * Check if a purchase is valid
     */
    def isValidPurchase(purchase: Purchase): Boolean = {
      purchase.quantity > 0 && purchase.unitPrice > 0.0 && purchase.customerID != "" && purchase.invoiceDate != "" &&
        purchase.country != "" && purchase.productID != ""
    }

}
