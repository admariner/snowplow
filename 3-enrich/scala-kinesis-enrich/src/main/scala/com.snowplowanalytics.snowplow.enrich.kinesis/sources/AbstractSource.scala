/*
 * Copyright (c) 2013-2014 Snowplow Analytics Ltd.
 * All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache
 * License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at
 * http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.
 *
 * See the Apache License Version 2.0 for the specific language
 * governing permissions and limitations there under.
 */
package com.snowplowanalytics
package snowplow.enrich
package kinesis
package sources

// Amazon
import com.amazonaws.auth._

// Scalaz
import scalaz.{Sink => _, _}
import Scalaz._

// json4s
import org.json4s.scalaz.JsonScalaz._
import org.json4s._
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._

// Iglu
import iglu.client.Resolver
import iglu.client.validation.ProcessingMessageMethods._

// Snowplow
import sinks._
import com.snowplowanalytics.maxmind.geoip.IpGeo
import common.outputs.{
  EnrichedEvent,
  BadRow
}
import common.loaders.ThriftLoader
import common.enrichments.EnrichmentRegistry
import common.enrichments.EnrichmentManager
import common.adapters.AdapterRegistry

import common.ValidatedMaybeCollectorPayload
import common.EtlPipeline
import common.utils.JsonUtils

/**
 * Abstract base for the different sources
 * we support.
 */
abstract class AbstractSource(config: KinesisEnrichConfig, resolverConfig: String, enrichmentConfig: String) {
  
  /**
   * Never-ending processing loop over source stream.
   */
  def run

  // Initialize a kinesis provider to use with a Kinesis source or sink.
  protected val kinesisProvider = createKinesisProvider

  // Initialize the sink to output enriched events to.
  protected val sink: Option[ISink] = config.sink match {
    case Sink.Kinesis => new KinesisSink(kinesisProvider, config).some
    case Sink.Stdouterr => new StdouterrSink().some
    case Sink.Test => None
  }

  /*private lazy val ipGeo = new IpGeo(
    dbFile = config.maxmindFile,
    memCache = false,
    lruCache = 20000
  )*/


  implicit val igluResolver: Resolver = Resolver.parse(JsonUtils.extractJson("", resolverConfig) match {
      case Success(r) => r
    }) bimap (
      e => throw new ArithmeticException(e.head.toString),
      s => s
    ) match {
      case Success(x) => x // TODO make this more rigorous
    }

  // Iterate through an enriched EnrichedEvent object and tab separate
  // the fields to a string.
  def tabSeparateEnrichedEvent(output: EnrichedEvent): String = {
    output.getClass.getDeclaredFields
    .map{ field =>
      field.setAccessible(true)
      Option(field.get(output)).getOrElse("")
    }.mkString("\t")
  }


  // TODO fill this in  
  def getEnrichmentRegistry: EnrichmentRegistry = {

    val registry = EnrichmentRegistry.parse(fromJsonNode(JsonUtils.extractJson("", enrichmentConfig) match {
      case Success(s) => s
    }), false)

    registry match {
      case Success(r) => r // TODO make this more rigorous
      case Failure(e) => throw new UnsupportedOperationException(e.head.toString)
    }
  }

  private val registry: EnrichmentRegistry = getEnrichmentRegistry

  // Helper method to enrich an event.
  // TODO: this is a slightly odd design: it's a pure function if our
  // our sink is Test, but it's an impure function (with
  // storeEnrichedEvent side effect) for the other sinks. We should
  // break this into a pure function with an impure wrapper.
  def enrichEvents(binaryData: Array[Byte]): List[Option[String]] = {
    val canonicalInput: ValidatedMaybeCollectorPayload = ThriftLoader.toCollectorPayload(binaryData)
    val processedEvents: List[ValidationNel[String, EnrichedEvent]] = EtlPipeline.processEvents(
      registry, s"kinesis-${generated.Settings.version}", System.currentTimeMillis.toString, canonicalInput)
    processedEvents.map(validatedMaybeEvent => {
      validatedMaybeEvent match {
        case Success(co) => {
          val ts = tabSeparateEnrichedEvent(co)
          for (s <- sink) {
            // TODO: pull this side effect into parent function
            s.storeEnrichedEvent(ts, co.user_ipaddress)
          }
          Some(ts)
        }
        case Failure(f) => {throw new ArithmeticException(f.toString); None} // TODO: https://github.com/snowplow/snowplow/issues/463
      }
    })

  }

  // Initialize a Kinesis provider with the given credentials.
  private def createKinesisProvider(): AWSCredentialsProvider =  {
    val a = config.accessKey
    val s = config.secretKey
    if (isCpf(a) && isCpf(s)) {
        new ClasspathPropertiesFileCredentialsProvider()
    } else if (isCpf(a) || isCpf(s)) {
      throw new RuntimeException(
        "access-key and secret-key must both be set to 'cpf', or neither"
      )
    } else if (isIam(a) && isIam(s)) {
      new InstanceProfileCredentialsProvider()
    } else if (isIam(a) || isIam(s)) {
      throw new RuntimeException("access-key and secret-key must both be set to 'iam', or neither of them")
    } else {
      new BasicAWSCredentialsProvider(
        new BasicAWSCredentials(a, s)
      )
    }
  }

  /**
   * Is the access/secret key set to the special value "iam" i.e. use
   * the IAM role to get credentials.
   *
   * @param key The key to check
   * @return true if key is iam, false otherwise
   */
  private def isIam(key: String): Boolean = (key == "iam")

  private def isCpf(key: String): Boolean = (key == "cpf")

  // Wrap BasicAWSCredential objects.
  class BasicAWSCredentialsProvider(basic: BasicAWSCredentials) extends
      AWSCredentialsProvider{
    @Override def getCredentials: AWSCredentials = basic
    @Override def refresh = {}
  }
}
