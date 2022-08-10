#!/bin/bash

spark-submit --class es.dmr.uimp.clustering.KMeansClusterInvoices --master local[4] target/scala-2.11/anomalyDetection-assembly-1.0.jar \
 ./src/main/resources/training.csv ./clustering ./threshold
spark-submit --class es.dmr.uimp.clustering.BisectionKMeansClusterInvoices --master local[4] target/scala-2.11/anomalyDetection-assembly-1.0.jar \
 ./src/main/resources/training.csv ./clustering_bisect ./threshold_bisect
