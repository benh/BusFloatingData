/*
 * Copyright 2016 Achim Nierbeck
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.nierbeck.floating.data.stream.spark

import java.util.{Date, Properties}

import breeze.linalg.DenseMatrix
import com.datastax.spark.connector._
import de.nierbeck.floating.data.domain._
import de.nierbeck.floating.data.tiler.TileCalc
import nak.cluster.{DBSCAN, GDBSCAN, Kmeans}
import org.apache.spark.rdd.RDD
import org.apache.spark.{SparkConf, SparkContext}
import org.joda.time.DateTime
import org.joda.time.format.{DateTimeFormat, DateTimeFormatter}
import org.slf4j.{Logger, LoggerFactory}

//noinspection ScalaStyle
object CalcClusterSparkApp {

  val log:Logger = LoggerFactory.getLogger(getClass.getName)

  import scala.language.implicitConversions

  /**
    * Executable main method of CalcClusterSparkApp.
    *
    * @param args - args(0): cassandra-host:port, arg(1): start_time
    *             for example: "2016-06-05 18:00:00"
    */
  def main(args: Array[String]) {

    assert(args.size >= 1, "Please provide the following params: cassandrahost:cassandraport start_time[optional]")
    val cassandraHost = args(0).split(":").head
    val cassandraPort = args(0).split(":").reverse.head

    val fmt:DateTimeFormatter = DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss");

    var startTime = DateTime.now().toString(fmt)

    if (args.length > 1)
      startTime = args(1)

    val sparkConf = new SparkConf()
      .setAppName(getClass.getName)
      .set("spark.cassandra.connection.host", cassandraHost )
      .set("spark.cassandra.connection.port", cassandraPort )
      .set("spark.cassandra.connection.keep_alive_ms", "30000")

    val sc = new SparkContext(sparkConf)

    val timeStartLimit:DateTime = fmt.parseDateTime(startTime)

    val timeStopLimit = timeStartLimit.plusHours(1)

    val vehiclesRdd:RDD[Vehicle] = sc.cassandraTable[Vehicle]("streaming", "vehicles").where("time > ? and time < ?", fmt.print(timeStartLimit), fmt.print(timeStopLimit))

    val vehiclesPos:Array[Double] = vehiclesRdd
      .flatMap(vehicle => Seq[(String, (Double,Double))]((s"${vehicle.id}_${vehicle.latitude}_${vehicle.longitude}",(vehicle.latitude, vehicle.longitude))))
      .reduceByKey((x,y) => x)
      .map(x => List(x._2._1, x._2._2)).flatMap(identity)
      .collect()

    val seqOfVehiclePos:Seq[Array[Double]] = Seq(vehiclesPos)

    log.info(s"got ${vehiclesPos.length} positions, first: ${vehiclesPos.head},${vehiclesPos.tail.head}")

    val vehiclePosRdd: RDD[Array[Double]] = sc.parallelize(seqOfVehiclePos)

    val denseMatrixRdd: RDD[DenseMatrix[Double]] = vehiclePosRdd.map(vehiclePosArray => DenseMatrix.create[Double](vehiclePosArray.length / 2, 2, vehiclePosArray))

    val clusterRdd: RDD[GDBSCAN.Cluster[Double]] = denseMatrixRdd.map(dm => dbscan(dm)).flatMap(identity)

    val clusterRddFiltered = clusterRdd/*.filter(cluster => cluster.points.size > 4)*/.cache()

    clusterRddFiltered.map{ cluster =>
      val timeStamp:Long = new Date().getTime
      val id:Long = cluster.id
      val points = cluster.points.map(_.value.toArray)
      points.zipWithIndex.map{tuple =>
        val points = correctLatLon(tuple._1(0), tuple._1(1))
        val index = tuple._2
        VehicleClusterDetails(id, index, timeStamp, points._1, points._2)
      }
    }.flatMap(identity)
      .saveToCassandra("streaming", "vehicleclusterdetails")

    val clusterdByKey = clusterRddFiltered.map(cluster => (cluster.id, cluster))

    val coordPointList:RDD[(Long,(Double,Double))] = clusterRddFiltered.map{ cluster =>
      val points: Seq[Array[Double]] = cluster.points.map(_.value.toArray)
      log.info(s"cluster: ${cluster}")

      val coords: List[Double] = points.toList.flatMap(x => x.toList)

      val coordTuples = convertListToTuple(coords, List.empty)

      (cluster.id, coordTuples.map(coordTuple => (points.size, convertToPoint(coordTuple))))
    }.map{pointListTuple =>
      (pointListTuple._1, pointListTuple._2.foldLeft((0, (0.0,0.0,0.0))) {
        case ((count,(accA,accB,accC)), (z,(a,b,c))) => ( z,  (accA + a, accB + b, accC + c))
      })
    }.map{ case ((id, (count, (a, b, c)))) =>
      (id, (a/count, b/count, c/count))
    }.map{ case ((id,(a,b,c))) =>
      import Math._
      val lon = atan2(b,a)
      val hyp = sqrt(a * a + b * b)
      val lat = atan2(c, hyp)
      (id, (lat * 180 / PI, lon * 180 / PI))
    }

    val clustered:RDD[VehicleCluster] = clusterdByKey.join(coordPointList).map{ case ( (clusterId, (cluster, (centerLat, centerLon))) )  =>
      VehicleCluster(clusterId.toInt, new Date().getTime, centerLat, centerLon, cluster.points.size)
    }

    clustered.saveToCassandra("streaming", "vehiclecluster")

    clustered.map(vehiclCluster => TiledVehicleCluster(TileCalc.convertLatLongToQuadKey(vehiclCluster.latitude, vehiclCluster.longitude), vehiclCluster.id, vehiclCluster.timeStamp, vehiclCluster.latitude, vehiclCluster.longitude, vehiclCluster.amount)).saveToCassandra("streaming", "vehiclecluster_by_tileid")

  }

  def convertToPoint(coordTuple:(Double,Double)):(Double,Double,Double) = {
    import Math._

    val lat = coordTuple._1 * PI / 180
    val lon = coordTuple._2 * PI / 180

    val a = cos(lat) * cos(lon)
    val b = cos(lat) * sin(lon)
    val c = sin(lat)

    (a,b,c)
  }

  def dbscan(v : breeze.linalg.DenseMatrix[Double]):Seq[GDBSCAN.Cluster[Double]] = {
    log.info(s"calculating cluster for denseMatrix: ${v.data.head}, ${v.data.tail.head}")
    val gdbscan = new GDBSCAN(
      DBSCAN.getNeighbours(epsilon = 0.0005, distance = Kmeans.euclideanDistance),
      DBSCAN.isCorePoint(minPoints = 3)
    )
    val clusters = gdbscan.cluster(v)
    clusters
  }

  def convertListToTuple(incomingList: List[Double], tupleList: List[(Double, Double)]): List[(Double, Double)] = {

    if (incomingList.size >= 2) {
      val newTuples: List[(Double, Double)] = tupleList :+ correctLatLon(incomingList.head, incomingList.tail.head)
      if (incomingList.size > 2)
        convertListToTuple(incomingList.tail.tail, newTuples)
      else
        newTuples
    } else {
      tupleList
    }
  }

  //this only works for LosAngeles
  private def correctLatLon(lat: Double, lon: Double) = {
    val MinLatitude = -85.05112878
    val MaxLatitude = 85.05112878
    val MinLongitude = -180
    val MaxLongitude = 180

    if (lat < MinLatitude || lat > MaxLatitude) {
      //obviously the cluster did switch the coordinates
      (lon, lat)
    } else {
      (lat, lon)
    }
  }


}
