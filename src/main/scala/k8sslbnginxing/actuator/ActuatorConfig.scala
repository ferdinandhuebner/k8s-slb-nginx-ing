package k8sslbnginxing.actuator
import akka.actor.ActorSystem
import com.google.common.base.Stopwatch
import io.fabric8.kubernetes.client.KubernetesClient
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.actuate.health.{Health, HealthIndicator}
import org.springframework.context.annotation.{Bean, Configuration}

@Configuration
class ActuatorConfig {

  @Autowired
  private var actorSystem: ActorSystem = _

  @Autowired
  private var kubernetesClient: KubernetesClient = _

  @Bean
  def kubernetesHealthIndicator: HealthIndicator = () => {
    try {
      val watch = Stopwatch.createStarted()
      kubernetesClient.services().inNamespace("default").withName("kubernetes").get()
      watch.stop()
      Health.up()
          .withDetail("get", "GET Service kubernetes@default in " + watch.toString)
          .build()
    } catch {
      case e: Exception =>
        Health.down(e).build()
    }
  }
}
