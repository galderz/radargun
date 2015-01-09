package org.radargun.service

import java.util.concurrent.ConcurrentHashMap

import akka.actor.ActorSystem
import org.radargun.Service
import org.radargun.service.TastService.TastCache
import org.radargun.traits.{ProvidesTrait, BasicOperations, ConditionalOperations}
import tast.{LocalStore, ReadWrite, Store}

import scala.concurrent.duration.{Duration, _}
import scala.concurrent.{Await, Future}

@Service(doc = "Tast - a store.")
class TastService { self =>

  protected var caches = new ConcurrentHashMap[String, TastCache[_, _]]

  private def getCache[K, V](cacheName: String): TastCache[K, V] = {
    var cache = caches.get(cacheName)
    if (cache == null) {
      cache = new TastCache(cacheName)
      val prev = caches.putIfAbsent(cacheName, cache)
      if (prev != null) cache = prev
    }
    cache.asInstanceOf[TastCache[K, V]]
  }

  @ProvidesTrait
  def createBasicOperations: BasicOperations = {
    new BasicOperations {
      def getCache[K, V](cacheName: String): BasicOperations.Cache[K, V] = {
        self.getCache(cacheName)
      }
    }
  }

  @ProvidesTrait
  def createConditionalOperations: ConditionalOperations = {
    new ConditionalOperations {
      def getCache[K, V](cacheName: String): ConditionalOperations.Cache[K, V] = {
        self.getCache(cacheName)
      }
    }
  }

}

object TastService {

  implicit val system = ActorSystem("TastSystem")

  class TastCache[K, V](name: String) extends BasicOperations.Cache[K, V]
      with ConditionalOperations.Cache[K, V] {
    val store: Store[K, V] = LocalStore[K, V](name)

    private def await[T](f: Future[T])(implicit d: Duration = 5.second): T = Await.result(f, d)

    override def get(key: K): V =
      // Using orNull results in compilation error: Cannot prove that Null <:< V
      await(store.get(key)).getOrElse(null).asInstanceOf[V]

    override def put(key: K, value: V): Unit = await(store.set(key, value))

    override def clear(): Unit = store.clear()

    override def getAndRemove(key: K): V = await {
      store.eval(key, ReadWrite) { e =>
        e.get().fold(null.asInstanceOf[V]) { v =>
           e.remove() // remove
           v // return previous value
        }
      }
    }

    override def remove(key: K): Boolean = await {
      store.eval(key, ReadWrite) { e =>
        e.get().fold(false) { v =>
          e.remove() // remove
          true // return true
        }
      }
    }

    override def containsKey(key: K): Boolean = await(store.get(key)).isDefined

    override def getAndPut(key: K, value: V): V = await {
      store.eval(key, ReadWrite) { e =>
        // Using orNull results in compilation error: Cannot prove that Null <:< V
        val old = e.get().getOrElse(null)
        e.set(value)
        old.asInstanceOf[V]
      }
    }

    override def replace(key: K, oldValue: V, newValue: V): Boolean =
      await(store.update(key, oldValue, newValue))

    override def replace(key: K, value: V): Boolean =
      await(store.update(key, value))

    override def remove(key: K, oldValue: V): Boolean =
      await(store.remove(key, oldValue))

    override def putIfAbsent(key: K, value: V): Boolean =
      await(store.insert(key, value))

    override def getAndReplace(key: K, value: V): V = await {
      store.eval(key, ReadWrite) { e =>
        e.get().fold(null.asInstanceOf[V]) { v =>
          e.set(value) // replace
          v // return previous value
        }
      }
    }

  }

}
