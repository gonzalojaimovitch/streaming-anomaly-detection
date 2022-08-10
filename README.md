# Anomaly Detection in a Streaming Scenario with Spark Streaming and Kafka

This repository contains the code for detecting anomalies from a stream of purchases, as well as detecting invalid invoices and the number of cancelled invoices in the last 8 minutes (updated every minute). The assumed SLA states that between every purchase of the same invoice the maximum time difference must be 40 seconds.


## Anomaly Detection Model Training

Two models will be used for detecting the anomalies while performing an A/B testing. These models will be the "KMeans" and "BisectionKMeans" from the MLlib clustering library.

The training is performed offline. For starting the training, the user can call the script "start_training.sh", stored in the root folder.


```bash
./start_training.sh
```


## Initialize the Simulation


### Kafka setup


First of all, the user needs to make sure that Zookeper and Kafka servers are initialized. All Kafka and Zookeeper related command should be run from the Kafka folder in /opt.


```bash
sudo bin/zookeeper-server-start.sh config/zookeeper.properties
```


```bash
sudo bin/kafka-server-start.sh config/server.properties
```


The existence of the required Kafka topics ("purchases", "anomalies_kmeans", "anomalies_kmeans_bisect", "cancelations_ma" and "invalid_invoices") must be checked:



```bash
bin/kafka-topics.sh --list --zookeeper localhost:2181

```

In case of not having the abovementioned topics created, they need to be created before executing the anomaly detection system.



```bash
bin/kafka-topics.sh --create bin/kafka-topics.sh --topic my_topic --partitions 1 --
replication-factor 1 --zookeeper localhost:2181
```


### Initalize the Streaming system


Within the root folder of the project, two main scripts need to be executed. 

The first one starts the Spark application. 

```bash
./start_pipeline.sh
```

The second one starts the streaming of purchases to the Kafka topic "purchases".

```bash
./productiondata.sh
```


### Read from the topics using the terminal

The user can read the contents of each of the Kafka topics where the application publishes using the following command.

```bash
bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic my_topic --from-beginning
```

