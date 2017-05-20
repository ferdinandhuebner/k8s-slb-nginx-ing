[![Build Status](https://travis-ci.org/ferdinandhuebner/k8s-slb-nginx-ing.svg?branch=master)](https://travis-ci.org/ferdinandhuebner/k8s-slb-nginx-ing)

# k8s-slb-nginx-ing

A kubernetes service load-balancer that (mis)uses the 
[nginx ingress](https://github.com/kubernetes/ingress/tree/master/controllers/nginx) controller's 
tcp/udp configmaps for l4 load-balancing

# Getting started

A minimal example that uses default configuration values, a service-account token or `~/.kube/config`
for kubernetes credentials, the ingress `prometheus-ingress@default` as a pillar for load-balancer ips,
and the config-maps `nginx-tcp-ingress-configmap@kube-system` and `nginx-udp-ingress-configmap@kube-system`

```bash
java -jar slb-controller.jar \
  --ingress.pillar=prometheus-ingress@default \
  --ingress.tcp-config-map=nginx-tcp-ingress-configmap@kube-system \
  --ingress.udp-config-map=nginx-udp-ingress-configmap@kube-system    
```

# Kubernetes Deployment

See [docs/deployment.md](docs/deployment.md) and [docs/deployment-rbac.md](docs/deployment-rbac.md).

# Configuration

See [docs/configuration.md](docs/configuration.md) for all configuration options.

# Liveness and readiness 

The application has [Spring Boot actuator](https://docs.spring.io/spring-boot/docs/current-SNAPSHOT/reference/htmlsingle/#production-ready) 
enabled at port 8080 at the context `/actuator`. You can use `/actuator/info` for readiness and
`/actuator/health` for liveness.

## Warning: Actuator security

Please note that actuator security is **disabled** by default. That means, that all actuator
features (including heapdumps, configuration information) can be accessed without a password.

If you want to change that, please set `--management.security.enabled=true` and configure a
username and password for http basic authentication with `--security.user.name=admin` and 
`--security.user.password=secret`.