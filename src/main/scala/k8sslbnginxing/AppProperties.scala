package k8sslbnginxing
import org.springframework.boot.context.properties.ConfigurationProperties

import scala.beans.BeanProperty

object AppProperties {

  object IngressProperties {
    def singlePorts(list: String): Set[Int] = {
      if (list == null || list.isEmpty) {
        Set.empty
      } else {
        list.split(",").filter(!_.contains("-")).map(_.trim().toInt).toSet
      }
    }
    def portRanges(list: String): Set[(Int, Int)] = {
      if (list == null || list.isEmpty) {
        Set.empty
      } else {
        list.split(",").filter(_.contains("-")).map(s => {
          val lo = s.trim().split("-")(0).trim().toInt
          val hi = s.trim().split("-")(1).trim().toInt
          if (hi <= lo) throw new IllegalArgumentException("not a valid port range: " + s)
          (lo, hi)
        }).toSet
      }
    }
  }

  @ConfigurationProperties(prefix = "ingress")
  class IngressProperties {
    @BeanProperty var tcpConfigMap: String = _
    @BeanProperty var udpConfigMap: String = _
    @BeanProperty var pillar: String = _
    @BeanProperty var portBlacklist: String = "80,442,443,0-1024,10240-10260,18080,30000-32767"
    @BeanProperty var portWhitelist: String = _
  }
}
