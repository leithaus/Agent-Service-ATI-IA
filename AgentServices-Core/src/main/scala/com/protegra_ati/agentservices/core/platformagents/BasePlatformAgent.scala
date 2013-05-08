/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.protegra_ati.agentservices.core.platformagents

import com.protegra_ati.agentservices.core.platformagents.behaviors._
import com.protegra_ati.agentservices.store.extensions.StringExtensions._
import com.protegra_ati.agentservices.store.extensions.ResourceExtensions._
import com.protegra_ati.agentservices.store.mongo.usage.AgentKVDBMongoScope._
import com.protegra_ati.agentservices.core.schema._
import com.protegra_ati.agentservices.store.mongo.usage.AgentKVDBMongoScope.mTT._
import com.protegra_ati.agentservices.core.messages._
import com.protegra_ati.agentservices.store.mongo.usage._
import com.protegra_ati.agentservices.core.util.rabbit.{RabbitConfiguration, MessageAMQPPublisher, MessageAMQPListener}
import java.util.concurrent.{ThreadFactory, TimeUnit, Executors}
import java.util.concurrent.atomic.AtomicInteger

import scala.util.continuations._
import scala.concurrent._

import java.net.URI
import java.util.UUID
import com.protegra_ati.agentservices.store.util._
import org.joda.time.DateTime
import com.protegra_ati.agentservices.core.util.serializer.Serializer
import com.protegra_ati.agentservices.core.util.ThreadRenamer._
import com.protegra_ati.agentservices.core.util.{ConfigurationManager, Results}

object BasePABaseXDefaults
{
  implicit val URI: String =
    "xmldb:basex://localhost:1984/"
  implicit val driver: String =
    "org.basex.api.xmldb.BXDatabase"
  implicit val dbRoot: String = "/db"
  implicit val createDB: Boolean = false
  implicit val indent: Boolean = false
  implicit val resourceType: String = "XMLResource"
  val queryServiceType: String = "XPathQueryService"
  val queryServiceVersion: String = "1.0"
  val managementServiceType: String =
    "CollectionManagementService"
  val managementServiceVersion: String = "1.0"
  val valueStorageType: String = "XStream"
  //why not   val valueStorageType : String = "CnxnCtxtLabel"
}

object FetchOrElseThreadFactory extends ThreadFactory
{
  final val threadNumber = new AtomicInteger(0)
  final val poolName = DateTime.now.toString("HHmmss")

  def newThread(r: Runnable) = {
    val t = Executors.defaultThreadFactory().newThread(r)
    t.setName("FetchOrElse-" + poolName + "-thread-" + threadNumber.incrementAndGet() )
    t
  }
}

object FetchOrElseScheduler {
  lazy val scheduler = Executors.newScheduledThreadPool(25, FetchOrElseThreadFactory)
}

object FetchOrElse

/**
 * Be careful, since this class extends FJTaskRunners, each instance of it creates it's own thread pool with defined in a method 'def numWorkers' size.
 */
abstract class BasePlatformAgent
  extends Reporting
  with ThreadPoolRunnersX
  //  with Scheduler
{
  /**
   * FJTaskRunners setting, defines thread pool size
   * @return threadpool size
   */
  //  override def numWorkers = 2 // TODO has to be out of config, as soon as configuration manager is separated from protunity services project

  var _id: UUID = null
  val TIMEOUT_LISTEN_TEMPORARY_FIX = 1000

  protected def agentCnxn(sourceId: UUID, targetId: UUID) = new AgentCnxnProxy(sourceId.toString.toURI, "", targetId.toString.toURI)

  def createNode(sourceAddress: URI, acquaintanceAddresses: List[ URI ]): Being.AgentKVDBNode[ PersistedKVDBNodeRequest, PersistedKVDBNodeResponse ] =
  {
    createNode(sourceAddress, acquaintanceAddresses, None)
  }

  def createNode(sourceAddress: URI, acquaintanceAddresses: List[ URI ], configFileName: Option[ String ]): Being.AgentKVDBNode[ PersistedKVDBNodeRequest, PersistedKVDBNodeResponse ] =
  {
    val space = AgentUseCase(configFileName)
    val node = space.createNode(sourceAddress, acquaintanceAddresses, configFileName)
    node
  }

  def initFromConfig(config: ConfigurationManager)
  {
    var id: UUID = null

    try {
      id = config.id
    } catch {
      case e: Exception => report("failed to load id from config", e, Severity.Fatal)
    }

    initFromConfig(config, id)
}

  def initFromConfig(config: ConfigurationManager, id: UUID)
  {
    _id = id
    init(config)
    startup()
  }

  def startup() {
    loadQueues()
    startListening()
  }

  protected def init(config: ConfigurationManager)

  //override with each specialized agent
  protected def loadQueues()

  protected def startListening()

  //deprecate these 3?
  //  def listen (queue: Being.AgentKVDBNode[ PersistedKVDBNodeRequest, PersistedKVDBNodeResponse ], cnxn: AgentCnxnProxy, channel:Channel.Value,  channelType:ChannelType.Value, handler:(AgentCnxnProxy, Message) => Unit) :Unit =
  //  {
  ////    val key = channel.toString + channelType.toString + "(_)"
  //    listen(queue, cnxn, channel, channelType, ChannelLevel.Private, handler)
  //  }

  //TODO: add some smarts around this to purge after certain size/length of time if we keep doing this instead of cursor
  //temporary solution is to ignore duplicate processing of the same request msg by id
  def listenRabbit(config: RabbitConfiguration, cnxn: AgentCnxnProxy, channel: Channel.Value, channelType: ChannelType.Value, channelLevel: ChannelLevel.Value, handler: ( Message ) => Unit): Unit =
  {
    listenRabbit(config, cnxn, channel, None, channelType, channelLevel, handler)
  }

  def listenRabbit(config: RabbitConfiguration, cnxn: AgentCnxnProxy, channel: Channel.Value, channelRole: Option[ ChannelRole.Value ], channelType: ChannelType.Value, channelLevel: ChannelLevel.Value, handler: ( Message ) => Unit): Unit =
  {
    //    val host = _privateLocation.host
    //    val port = _privateLocation.port
    spawn {
      val key = channel.toString + channelRole.getOrElse("") + channelType.toString + channelLevel.toString + "(_)"
      val exchange = cnxn.getExchangeKey + key
      val routingKey = "routeroute"

      val listener = new MessageAMQPListener(config, exchange, routingKey, handler(_: Message))
    }
  }

  //make everything below here protected once tests are sorted out
  def sendRabbit(config: RabbitConfiguration, cnxn: AgentCnxnProxy, msg: Message)
  {
    spawn {
      report("send --- key: " + msg.getExchangeKey + " cnxn: " + cnxn.toString, Severity.Trace)
      if ( msg.eventKey != null ) {
        report("send --- eventKey: " + msg.eventKey.toString, Severity.Trace)
      }
      //    val host = _privateLocation.host
      //    val port = _privateLocation.port
      val exchange = cnxn.getExchangeKey + msg.getExchangeKey
      val routingKey = "routeroute"
      try {
        MessageAMQPPublisher.sendToRabbit(config, exchange, routingKey, msg)
      }
      catch {
        case e => {
          report("sendRabbit exception, key: " + msg.getExchangeKey + " cnxn: " + cnxn.toString + "  exception: ", e, Severity.Error)
        }
      }
    }
  }

  def listen(queue: Being.AgentKVDBNode[ PersistedKVDBNodeRequest, PersistedKVDBNodeResponse ], cnxn: AgentCnxnProxy, channel: Channel.Value, channelType: ChannelType.Value, channelLevel: ChannelLevel.Value, handler: (AgentCnxnProxy, Message) => Unit): Unit =
  {
    listen(queue, cnxn, channel, None, channelType, channelLevel, handler)
  }

  def listen(queue: Being.AgentKVDBNode[ PersistedKVDBNodeRequest, PersistedKVDBNodeResponse ], cnxn: AgentCnxnProxy, channel: Channel.Value, channelRole: Option[ ChannelRole.Value ], channelType: ChannelType.Value, channelLevel: ChannelLevel.Value, handler: (AgentCnxnProxy, Message) => Unit): Unit =
  {
    val key = channel.toString + channelRole.getOrElse("") + channelType.toString + channelLevel.toString + "(_)"
    listen(queue, cnxn, key, handler, None)
  }

  // TODO we are continue to listen on especial channel after one message is consumed and not expired yet. Potentially we have one waiting thread per channel, if no expired message is recived.
  // TODO solution: to create artificial expired dummy message as soon as we have enought results or timeout, so that we don't need to continue to wait
  def listen(queue: Being.AgentKVDBNode[ PersistedKVDBNodeRequest, PersistedKVDBNodeResponse ], cnxn: AgentCnxnProxy, key: String, handler: (AgentCnxnProxy, Message) => Unit, expiry: Option[ DateTime ]): Unit =
  {
    val lblChannel = key.toLabel

    val agentCnxn = cnxn.toAgentCnxn()
    report("listen: channel: " + lblChannel.toString + " id: " + _id + " cnxn: " + cnxn.toString + " key: " + key, Severity.Debug)

    //really should be a subscribe but can only be changed when put/subscribe works. get is a one listen deal.
    reset {
      try {
        for ( e <- queue.subscribe(agentCnxn)(lblChannel) ) {
          //        for ( e <- queue.get(agentCnxn)(lblChannel) ) {
          val expired = isExpired(expiry)
          if ( e != None && !expired ) {

            //keep the main thread listening, see if this causes debug headache
            // STRESS TODO separation between different ways how to create/mange threads for different type of requests:
            //        - for long term running jobs (like referral request with continuations, classical scala default 'spawn' which runs a new Thread per spawn is atractiv)
            //        - for short lived requests thread pool is nost attractive to keep number of threads under control
            //        - on KBDB level timeout for continuations is necessary so thread from thread pool for short lived requests can be released after given time
            //        - HOW continuations are working with running threads !!!!
            spawn {
              //rename {
              val msg = Serializer.deserialize[ Message ](e.dispatch)
              //println("IIIIIIIIIIIIIIIIIIIIIIII msg id : " + msg.ids.id + " on cnxn " + cnxn)
              report("!!! Listen Received !!!: " + msg.toString.short + " channel: " + lblChannel + " msg id: " + msg.ids.id + " cnxn: " + agentCnxn.toString, Severity.Debug)
              //race condition on get get get with consume bringing back the same item, cursor would get around this problem
              //BUG 54 - can't use a cursor get before a put because no results are returned, problem with cursors and waiters
              //temporary solution is to ignore duplicate processing of the same request msg by id
              val msgKey = key + msg.ids.id
              if ( !MemCache.hasValue(msgKey)(Results.client) ) {
                //              if ( !_processedMessages.contains(key + msg.ids.id) ) {
                //                _processedMessages.add(key + msg.ids.id)
                MemCache.set(msgKey, "1", 180)(Results.client)
                handler(cnxn, msg)
              }
              else
                report("already processed id : " + msg.ids.id, Severity.Debug)
              //            ("inBasePlatformAgent listen on channel in a loop: " + lblChannel)
            }
            //listen(queue, cnxn, key, handler, expiry)
          }
          else {
            report("listen received - none", Severity.Debug)
          }
        }
      } catch {
        case e: Exception => report("KVDB subscribe operation failed", e, Severity.Error)
      }
    }
  }

  def isExpired(expiry: Option[ DateTime ]): Boolean =
  {
    expiry match {
      case None =>
        false
      case Some(x: DateTime) => {
        if ( x.isBeforeNow ) true
        else false
      }
      case _ => {
        false
      }
    }
  }

  //  //new style
  //  def listenList(queue: Being.AgentKVDBNode[ PersistedKVDBNodeRequest, PersistedKVDBNodeResponse ], cnxn: AgentCnxnProxy, key:String, handler:(AgentCnxnProxy, List[Message]) => Unit) :Unit =
  //  {
  //    val lblChannel = key.toLabel
  //
  //    report("listen: channel: " + lblChannel.toString + " id: " + _id + " cnxn: " + cnxn.toString + " key: " + key, Severity.Debug)
  //
  //    //really should be a subscribe but can only be changed when put/subscribe works. get is a one listen deal.
  //    reset {
  //      for( e <- queue.get( true )( cnxn )(lblChannel))
  //      {
  //        if ( e != None ) {
  //          spawn {
  //            val results: List[ Message ] = e.dispatchCursor.toList.map(x => Serializer.deserialize[ Message ](x.dispatch))
  //            results.map(x => report("!!! Listen Received !!!: " + x.toString.short + " channel: " + lblChannel + " id: " + _id + " cnxn: " + cnxn.toString, Severity.Debug))
  //            handler(cnxn, results)
  //          }
  //          //keep the main thread listening, see if this causes debug headache
  //          listenList(queue, cnxn, key, handler)
  //        }
  //        else {
  //          report("listen received - none", Severity.Debug)
  //        }
  //      }
  //    }
  //  }

  def singleListen[ T ](queue: Being.AgentKVDBNode[ PersistedKVDBNodeRequest, PersistedKVDBNodeResponse ], cnxn: AgentCnxnProxy, key: String, handler: (AgentCnxnProxy, T) => Unit): Unit =
  {
    val lblChannel = key.toLabel

    val agentCnxn = cnxn.toAgentCnxn()
    report("listen: channel: " + lblChannel.toString + " id: " + _id + " cnxn: " + agentCnxn.toString + " key: " + key, Severity.Debug)

    //really should be a subscribe but can only be changed when put/subscribe works. get is a one listen deal.
    reset {
      try {
        for ( e <- queue.subscribe(agentCnxn)(lblChannel) ) {
          if ( e != None ) {
            //keep the main thread listening, see if this causes debug headache
            spawn {
              rename {
                val msg = Serializer.deserialize[ T ](e.dispatch)
                report("!!! Listen Received !!!: " + msg.toString.short + " channel: " + lblChannel + " id: " + _id + " cnxn: " + agentCnxn.toString, Severity.Debug)
                handler(cnxn, msg)
              }("inBasePlatformAgent single listen on channel: " + lblChannel)
            }
          }
          else {
            report("listen received - none", Severity.Debug)
          }
        }
      } catch {
        case e: Exception => report("KVDB subscribe operation failed", e, Severity.Error)
      }
    }
  }

  //the only public method to be used by apps is send
  //apps should just be concerned with sending types of request messages and listening for response events
  //the apps only needs to deal with the message q level
  //  def send (msg: Message, sourceId:UUID, targetId:UUID ): Unit =
  //  {
  //    //TODO: implement serialization of the message object
  //    send(_publicQ, agentCnxn(sourceId, targetId), msg)
  //  }

  //make everything below here protected once tests are sorted out
  def send(queue: Being.AgentKVDBNode[ PersistedKVDBNodeRequest, PersistedKVDBNodeResponse ], cnxn: AgentCnxnProxy, msg: Message)
  {
//    println("SSSSSSSSSSSSSSSSSSSSSS sending a response with id " + msg.ids.id)
    report("send --- key: " + msg.getChannelKey + " cnxn: " + cnxn.toString, Severity.Trace)
    if ( msg.eventKey != null ) {
      report("send --- eventKey: " + msg.eventKey.toString, Severity.Trace)
    }
    publish(queue, cnxn, msg.getChannelKey, Serializer.serialize[ Message ](msg))
  }

  def singleSend(queue: Being.AgentKVDBNode[ PersistedKVDBNodeRequest, PersistedKVDBNodeResponse ], cnxn: AgentCnxnProxy, msg: Message)
  {
    msg.channelLevel = Some(ChannelLevel.Single)
//    println("SSSSSSSSSSSSSSSSSSSSSS single sending a response with id " + msg.ids.id)
    report("send --- key: " + msg.getChannelKey + " cnxn: " + cnxn.toString, Severity.Trace)
    if ( msg.eventKey != null ) {
      report("send --- eventKey: " + msg.eventKey.toString, Severity.Trace)
    }
    publish(queue, cnxn, msg.getChannelKey, Serializer.serialize[ Message ](msg))
  }

  def put(queue: Being.AgentKVDBNode[ PersistedKVDBNodeRequest, PersistedKVDBNodeResponse ], cnxn: AgentCnxnProxy, key: String, value: String) =
  {
    publish(queue, cnxn, key, value)
//    val agentCnxn = cnxn.toAgentCnxn()
//    report("put --- key: " + key + ", value: " + value.short + " cnxn: " + cnxn.toString)
//    val lbl = key.toLabel
//    reset {queue.put(agentCnxn)(lbl, Ground(value))}
  }

  def publish(queue: Being.AgentKVDBNode[ PersistedKVDBNodeRequest, PersistedKVDBNodeResponse ], cnxn: AgentCnxnProxy, key: String, value: String) =
  {

    val agentCnxn = cnxn.toAgentCnxn()
    report("publish --- key: " + key + ", value: " + value.short + " cnxn: " + cnxn.toString)
    val lbl = key.toLabel
    reset {queue.publish(agentCnxn)(lbl, Ground(value))}
  }

  def get[ T ](queue: Being.AgentKVDBNode[ PersistedKVDBNodeRequest, PersistedKVDBNodeResponse ], cnxn: AgentCnxnProxy, key: String, handler: (AgentCnxnProxy, T) => Unit) =
  {
    report("get --- key: " + key)
    val lbl = key.toLabel

    val agentCnxn = cnxn.toAgentCnxn()
    var result = ""
    reset {
      try {
        for ( e <- queue.subscribe(agentCnxn)(lbl) ) {
          if ( e != None ) {
            //multiple results will call handler multiple times
            handler(cnxn, Serializer.deserialize[ T ](e.dispatch))
          }
        }
      } catch {
        case e: Exception => report("KVDB subscribe operation failed", e, Severity.Error)
      }
    }
  }

//  def getList[ T ](queue: Being.AgentKVDBNode[ PersistedKVDBNodeRequest, PersistedKVDBNodeResponse ], cnxn: AgentCnxnProxy, key: String, handler: (AgentCnxnProxy, List[ T ]) => Unit) =
//  {
//    report("get --- key: " + key + " cnxn: " + cnxn.toString, Severity.Debug)
//    val lbl = key.toLabel
//
//    val agentCnxn = cnxn.toAgentCnxn()
//    reset {
//      for ( e <- queue.subscribe(true)(agentCnxn)(lbl) ) {
//        if ( e != None ) {
//          val results: List[ T ] = e.dispatchCursor.toList.map(x => Serializer.deserialize[ T ](x.dispatch))
//          handler(cnxn, results)
//        }
//      }
//    }
//  }

  def getData(queue: Being.AgentKVDBNode[ PersistedKVDBNodeRequest, PersistedKVDBNodeResponse ], cnxn: AgentCnxnProxy, key: String, handler: (AgentCnxnProxy, Data) => Unit) =
  {
    report("get --- key: " + key)
    val lbl = key.toLabel

    val agentCnxn = cnxn.toAgentCnxn()
    var result = ""
    reset {
      try {
        for ( e <- queue.subscribe(agentCnxn)(lbl) ) {
          if ( e != None ) {
            //multiple results will call handler multiple times
            handler(cnxn, Serializer.deserialize[ Data ](e.dispatch))
          }
        }
      } catch {
        case e: Exception => report("KVDB subscribe operation failed", e, Severity.Error)
      }
    }
  }

  def store(queue: Being.AgentKVDBNode[ PersistedKVDBNodeRequest, PersistedKVDBNodeResponse ], cnxn: AgentCnxnProxy, key: String, value: String) =
  {
    report("store --- key: " + key + ", cnxn: " + cnxn.toString + ", value: " + value.short, Severity.Trace)
    val lbl = key.toLabel
    val agentCnxn = cnxn.toAgentCnxn()
    //this should really be store
    //    reset {queue.put(agentCnxn)(lbl, Ground(value))}
    queue.store(agentCnxn)(lbl, Ground(value))
  }


  def fetch[ T ](queue: Being.AgentKVDBNode[ PersistedKVDBNodeRequest, PersistedKVDBNodeResponse ], cnxn: AgentCnxnProxy, key: String, handler: (AgentCnxnProxy, T) => Unit) =
  {
    report("fetch --- key: " + key + " cnxn: " + cnxn.toString, Severity.Trace)
    val lbl = key.toLabel

    val agentCnxn = cnxn.toAgentCnxn()
    reset {
      try {
        for ( e <- queue.read(agentCnxn)(lbl) ) {

          if ( e != None ) {
            //multiple results will call handler multiple times
            val result = Serializer.deserialize[ T ](e.dispatch)
            if ( result != null )
              handler(cnxn, result)
          }
        }
      } catch {
        case e: Exception => report("KVDB read operation failed", e, Severity.Error)
      }
    }
  }

  def fetchOrElse[ T ](queue: Being.AgentKVDBNode[ PersistedKVDBNodeRequest, PersistedKVDBNodeResponse ], cnxn: AgentCnxnProxy, key: String, handler: (AgentCnxnProxy, T) => Unit)
    (retries: Int, delay: Int, handlerElse: () => Unit): Unit =
  {
    report("fetchOrElse --- key: " + key + " cnxn: " + cnxn.toString, Severity.Trace)
    val lbl = key.toLabel

    val agentCnxn = cnxn.toAgentCnxn()
    var found = false
    reset {
        try {
          for ( e <- queue.read(agentCnxn)(lbl) ) {
            if ( e != None ) {
              //multiple results will call handler multiple times
              val result = Serializer.deserialize[ T ](e.dispatch)
              if ( result != null )
              {
                handler(cnxn, result)
                found = true
              }
            }
          }
        } catch {
          case e: Exception => report("KVDB read operation failed", e, Severity.Error)
        }
    }

    if (!found)
    {
      if (retries > 0) {
        FetchOrElseScheduler.scheduler.schedule(new Runnable() {
          def run(): Unit = {
            fetchOrElse[T](queue, cnxn, key, handler)(retries-1, delay, handlerElse)
          }
        }, delay, TimeUnit.MILLISECONDS)
      } else {
        handlerElse()
      }
    }
  }


  def fetchList[ T ](queue: Being.AgentKVDBNode[ PersistedKVDBNodeRequest, PersistedKVDBNodeResponse ], cnxn: AgentCnxnProxy, key: String, handler: (AgentCnxnProxy, List[ T ]) => Unit) =
  {
    report("fetch --- key: " + key + " cnxn: " + cnxn.toString, Severity.Trace)
    val lbl = key.toLabel

    val agentCnxn = cnxn.toAgentCnxn()
    reset {
      try {
        for ( e <- queue.read(true)(agentCnxn)(lbl) ) {
          if ( e != None ) {
            val results: List[ T ] = e.dispatchCursor.toList.map(x => Serializer.deserialize[ T ](x.dispatch))
            val cleanResults = results.filter(x => x != null)
            handler(cnxn, cleanResults)
          }
        }
      } catch {
        case e: Exception => report("KVDB read operation failed", e, Severity.Error)
      }
    }
  }

  def fetchListOrElse[ T ](queue: Being.AgentKVDBNode[ PersistedKVDBNodeRequest, PersistedKVDBNodeResponse ], cnxn: AgentCnxnProxy, key: String, handler: (AgentCnxnProxy, List[ T ]) => Unit)
                          (retries: Int, delay: Int, handlerElse: () => Unit): Unit =
  {
    report("fetchListOrElse --- key: " + key + " cnxn: " + cnxn.toString, Severity.Trace)
    val lbl = key.toLabel

    val agentCnxn = cnxn.toAgentCnxn()
    var found = false
    reset {
        try {
          for ( e <- queue.read(true)(agentCnxn)(lbl) ) {
            if ( e != None ) {
              val results: List[ T ] = e.dispatchCursor.toList.map(x => Serializer.deserialize[ T ](x.dispatch))
              val cleanResults = results.filter(x => x != null)

              cleanResults match {
                case Nil => Thread.sleep(delay)
                case _ => {
                  handler(cnxn, cleanResults)
                  found = true
                }
              }
            }
          }
        } catch {
          case e: Exception => report("KVDB read operation failed", e, Severity.Error)
        }
      }

    if (!found)
    {
      if (retries > 0) {
        FetchOrElseScheduler.scheduler.schedule(new Runnable() {
          def run(): Unit = {
            fetchListOrElse[T](queue, cnxn, key, handler)(retries-1, delay, handlerElse)
          }
        }, delay, TimeUnit.MILLISECONDS)
      } else {
        handlerElse()
      }
    }
  }

  /**
   * Extended fetch to run different searches at once asynchronously.
   * The search happens recursively, so that results of the previous search will be passed to the next search etc.
   * At the end if all searches were successfully, the handler will be executed whole result list will be passed int it.
   * It worce it to define keyList so, that at the begin of the list will stay the search keys for the objects which will probably not found, this way it is possible to reduce the search depth
   * @param queue queue for fetch
   * @param cnxn connection
   * @param keyList list of the search keys for different searches. Has to be distinct from Nil, otherwise NoSuchElementException will be raised
   * @param handler to be executed at the end of the search with all search results, won't be executed if one of the searches fails
   * @tparam T type of the data to be fetched, if different types are expected, use a common interface
   * @return
   */
  def fetchList[ T ](queue: Being.AgentKVDBNode[ PersistedKVDBNodeRequest, PersistedKVDBNodeResponse ], cnxn: AgentCnxnProxy, keyList: List[ String ], handler: (AgentCnxnProxy, List[ T ]) => Unit) =
  {
    recursiveFetch(queue, cnxn, keyList, Nil, handler)
  }

  protected def recursiveFetch[ T ](queue: Being.AgentKVDBNode[ PersistedKVDBNodeRequest, PersistedKVDBNodeResponse ], cnxn: AgentCnxnProxy, remainKeyList: List[ String ], intermediateResults: List[ T ], finalHandler: (AgentCnxnProxy, List[ T ]) => Unit): Unit =
  {
    val lbl = remainKeyList.head.toLabel

    val agentCnxn = cnxn.toAgentCnxn()
    reset {
      try {
        for ( e <- queue.read(true)(agentCnxn)(lbl) ) {
          if ( e != None ) {
            val results: List[ T ] = e.dispatchCursor.toList.map(x => Serializer.deserialize[ T ](x.dispatch))
            val newRemainKeyList = remainKeyList.tail
            val newIntermediateResults = intermediateResults ::: results
            // last search is performed execute final handler
            if ( newRemainKeyList.isEmpty ) finalHandler(cnxn, newIntermediateResults)
            // next step of the fetch
            else recursiveFetch(queue, cnxn, newRemainKeyList, newIntermediateResults, finalHandler)
          }
        }
      } catch {
        case e: Exception => report("KVDB read operation failed", e, Severity.Error)
      }
    }
  }

  //note:  this doesn't work with wildcards right now
  //delete must use an exact key, no unification like get/fetch use occurs
  def delete(queue: Being.AgentKVDBNode[ PersistedKVDBNodeRequest, PersistedKVDBNodeResponse ], cnxn: AgentCnxnProxy, key: String) =
  {
    val agentCnxn = cnxn.toAgentCnxn()
    report("delete --- key: " + key.toLabel + " cnxn: " + cnxn.toString, Severity.Trace)
    queue.delete(agentCnxn)(key.toLabel)
  }

  def drop(queue: Being.AgentKVDBNode[ PersistedKVDBNodeRequest, PersistedKVDBNodeResponse ], cnxn: AgentCnxnProxy) =
  {
    val agentCnxn = cnxn.toAgentCnxn()
    report("drop --- cnxn: " + cnxn.toString, Severity.Trace)
    queue.drop(agentCnxn)
  }


  def createAgentCnxn(src: String, label: String, trgt: String) =
  {
    new AgentCnxnProxy(( src ).toURI, label, ( trgt ).toURI)
  }


  //// tests for when BUG 54 is fixed
  //  def listenCursor(queue: Being.AgentKVDBNode[ PersistedKVDBNodeRequest, PersistedKVDBNodeResponse ], cnxn: AgentCnxnProxy, channel:Channel.Value, channelType:ChannelType.Value, channelLevel:ChannelLevel.Value, handler:(AgentCnxnProxy, Message) => Unit) :Unit =
  //  {
  //    listenCursor(queue, cnxn, channel, None, channelType, channelLevel, handler)
  //  }
  //  def listenCursor(queue: Being.AgentKVDBNode[ PersistedKVDBNodeRequest, PersistedKVDBNodeResponse ], cnxn: AgentCnxnProxy, channel:Channel.Value, channelRole:Option[ChannelRole.Value], channelType:ChannelType.Value, channelLevel:ChannelLevel.Value, handler:(AgentCnxnProxy, Message) => Unit) :Unit =
  //  {
  //    val key = channel.toString + channelRole.getOrElse("") + channelType.toString + channelLevel.toString + "(_)"
  //    listenCursor(queue, cnxn, key, handler)
  //  }
  //  def listenCursor(queue: Being.AgentKVDBNode[ PersistedKVDBNodeRequest, PersistedKVDBNodeResponse ], cnxn: AgentCnxnProxy, key:String, handler:(AgentCnxnProxy, Message) => Unit) :Unit =
  //    {
  //      val lblChannel = key.toLabel
  //
  //      report("listen: channel: " + lblChannel.toString + " id: " + _id + " cnxn: " + cnxn.toString + " key: " + key, Severity.Debug)
  //
  //      //really should be a subscribe but can only be changed when put/subscribe works. get is a one listen deal.
  //      reset {
  //        for( c <- queue.get( true )( cnxn )(lblChannel))
  //        {
  //          report("LISTENED TO CURSOR -------------- " + c.toString, Severity.Debug)
  //          if (c != None)
  //          {
  //  //          spawn {
  //            val iter = c.dispatchCursor
  //            for ( e <- iter ) {
  //              val msg = Serializer.deserialize[ Message ](e.dispatch)
  //              report("!!! Listen Received !!!: " + msg.toString.short + " channel: " + lblChannel + " id: " + _id + " cnxn: " + cnxn.toString, Severity.Debug)
  //              handler(cnxn, msg)
  //            }
  //  //            val messages: List[ Message ] = e.dispatchCursor.toList.map(x => Serializer.deserialize[ Message ](x.dispatch))
  //  //            messages.foreach(msg => {
  //  //              spawn {
  //  //              }
  //  //          }
  //            //keep the main thread listening, see if this causes debug headache
  //
  //            listenCursor(queue, cnxn, key, handler)
  //          }
  //        else {
  //            report("listen received - none", Severity.Debug)
  //          }
  //        }
  //      }
  //    }

}
