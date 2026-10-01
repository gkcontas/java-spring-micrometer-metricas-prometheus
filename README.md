# Métricas com Micrometer, Prometheus e Grafana

API de pedidos instrumentada de ponta a ponta, com a stack de observabilidade completa subindo junto: aplicação, Prometheus e Grafana com dashboard e regras de alerta já provisionados.

O projeto mostra os quatro tipos primitivos de métrica do Micrometer, cada um em um caso em que é o tipo certo, e o que fazer com eles depois: histogramas orientados ao objetivo de serviço, controle de cardinalidade, métricas de negócio ao lado das técnicas e health check publicado como série temporal.

## As métricas

| Métrica | Tipo | Dimensões | O que responde |
|---|---|---|---|
| `orders.accepted` | Counter | canal, status | Volume de pedidos aceitos |
| `orders.pending` | Gauge | — | Tamanho atual da fila de processamento |
| `orders.processing` | Timer | resultado | Latência de processamento, em buckets |
| `orders.amount` | DistributionSummary | canal | Distribuição do valor dos pedidos |
| `orders.payment.failures` | Counter | motivo | Falhas de pagamento por causa |
| `component.health` | Gauge | componente | Disponibilidade do gateway, com histórico |

Além dessas, o Actuator publica sozinho as métricas de JVM, GC, threads, pool de conexões e latência HTTP por rota.

A diferença entre as duas famílias é o ponto do projeto: as técnicas dizem se o processo está de pé, as de negócio dizem se o produto está funcionando. "Pedidos aceitos caiu a zero" é um incidente que um painel de JVM verde não mostra.

## Tecnologias e bibliotecas

| | |
|---|---|
| Linguagem | Java 21 (virtual threads no gerador de carga) |
| Framework | Spring Boot 3.5, Spring Boot Actuator |
| Métricas | Micrometer + `micrometer-registry-prometheus` |
| Anotações | Spring AOP, para `@Timed` e `@Counted` |
| Persistência | Spring Data JPA, PostgreSQL 16 |
| Migrations | Flyway |
| Observabilidade | Prometheus 2.53, Grafana 11.1 |
| Build | Gradle Kotlin DSL (wrapper `gradlew`) |
| Testes | JUnit 5, `SimpleMeterRegistry`, Testcontainers |
| Apoio | Lombok |

## Pré-requisitos

- JDK 21 ou superior
- Docker e Docker Compose

## Como rodar

A stack completa sobe com dois comandos:

```bash
./gradlew bootJar
```

```bash
docker compose up -d --build
```

| | |
|---|---|
| Aplicação | `http://localhost:8080` |
| Métricas da aplicação | `http://localhost:8080/actuator/prometheus` |
| Prometheus | `http://localhost:9090` |
| Grafana | `http://localhost:3000` — acesso anônimo, com o dashboard **Pedidos — observabilidade** já provisionado |

Para encerrar:

```bash
docker compose down -v
```

Para rodar a aplicação fora do compose (pela IDE ou com `./gradlew bootRun`), suba só o banco com `docker compose up -d postgres`. Nesse modo o Prometheus não alcança a aplicação, mas as métricas continuam disponíveis em `/actuator/prometheus`.

## Gere carga antes de olhar os painéis

Um dashboard contra um serviço parado não mostra nada: os buckets ficam vazios e todo gráfico é uma linha no zero. O endpoint de carga cria e processa pedidos sintéticos, com latência de cauda longa e uma fração de falhas, para os painéis terem o que exibir.

```bash
curl -s -X POST "localhost:8080/load?orders=300"
```

## Endpoints

| Método | Rota | Descrição |
|---|---|---|
| `POST` | `/orders` | Cria um pedido |
| `GET` | `/orders/{id}` | Consulta um pedido |
| `POST` | `/orders/{id}/process` | Processa com instrumentação programática |
| `POST` | `/orders/{id}/process-annotated` | Processa com `@Timed` |
| `POST` | `/load?orders=N` | Gera carga sintética |
| `POST` | `/demo/cardinality?customers=N` | Compara uma dimensão ilimitada com uma limitada |
| `POST` | `/demo/gateway?reachable=false` | Simula o gateway de pagamento fora do ar |
| `POST` | `/demo/gauge-reference` | Mostra o efeito do ciclo de vida do objeto medido por um gauge |
| `GET` | `/actuator/health` | Health check, incluindo o indicador do gateway |
| `GET` | `/actuator/prometheus` | Métricas no formato de exposição |

## Exemplos de uso

```bash
curl -s -X POST localhost:8080/orders -H "Content-Type: application/json" \
  -d '{"customerEmail":"ana@example.com","amount":199.90,"channel":"WEB"}'
```

```bash
curl -s -X POST localhost:8080/orders/1/process
```

```bash
curl -s localhost:8080/actuator/prometheus | grep orders_
```

Derrubar o gateway move o health check e a métrica de disponibilidade ao mesmo tempo:

```bash
curl -s -X POST "localhost:8080/demo/gateway?reachable=false"
```

```bash
curl -si localhost:8080/actuator/health | head -1
```

```bash
curl -s -X POST "localhost:8080/demo/gateway?reachable=true"
```

## O dashboard

São 12 painéis, provisionados por arquivo — nada precisa ser clicado para aparecer. Algumas das consultas:

```promql
# Pedidos por minuto, por canal
sum by (channel) (rate(orders_accepted_total[1m])) * 60

# Fração de pedidos dentro do objetivo de 300 ms
sum(rate(orders_processing_seconds_bucket{le="0.3"}[5m]))
  / sum(rate(orders_processing_seconds_count[5m]))

# p95 do serviço inteiro, calculado a partir dos buckets
histogram_quantile(0.95, sum by (le) (rate(orders_processing_seconds_bucket[5m])))

# Falhas de pagamento por motivo
sum by (reason) (rate(orders_payment_failures_total[5m])) * 60

# Latência HTTP por rota
histogram_quantile(0.95, sum by (le, uri) (rate(http_server_requests_seconds_bucket[5m])))
```

Os limites dos histogramas são escolhidos em torno do objetivo de serviço, e não pelo conjunto padrão: com um bucket exatamente em 300 ms, a pergunta "quantos pedidos cumpriram o SLO" é respondida com exatidão.

## Alertas

Cinco regras provisionadas no Prometheus:

| Alerta | Dispara quando |
|---|---|
| `NoOrdersAccepted` | Nenhum pedido aceito por cinco minutos |
| `PaymentFailureRateHigh` | Mais de um quarto dos pagamentos falhando |
| `ProcessingSloBurn` | Menos de 95% dos pedidos dentro de 300 ms |
| `PaymentGatewayDown` | Gateway inalcançável de forma contínua |
| `InstanceDown` | Instância parou de responder aos scrapes |

As regras são escritas contra o objetivo, não contra um percentil: "95% dos pedidos em menos de 300 ms" é uma frase com que alguém concordou, enquanto "o p95 é 310 ms" precisa ser traduzida toda vez que é lida.

## Estrutura da stack

```
docker-compose.yml                            aplicação, PostgreSQL, Prometheus e Grafana
docker/prometheus/prometheus.yml              configuração de scrape
docker/prometheus/alert-rules.yml             as cinco regras
docker/grafana/provisioning/datasources/      datasource apontando para o Prometheus
docker/grafana/provisioning/dashboards/       carrega os dashboards do diretório abaixo
docker/grafana/dashboards/orders.json         os 12 painéis
```

## Testes

```bash
./gradlew test
```

34 testes. Os unitários usam `SimpleMeterRegistry`, um registry em memória com a mesma semântica do real, e os de integração verificam o texto que o Prometheus realmente raspa, contra um PostgreSQL em container.

| Classe | O que cobre |
|---|---|
| `OrderMetricsTest` | Os quatro tipos de métrica e suas dimensões |
| `MeterFilterTest` | Tags comuns, buckets de SLO e teto de cardinalidade |
| `GaugeReferenceDemoTest` | Ciclo de vida do objeto medido por um gauge |
| `PrometheusExpositionIntegrationTest` | Nomes e rótulos no formato de exposição |
| `AnnotatedTimerIntegrationTest` | `@Timed` através do proxy AOP |
| `CardinalityIntegrationTest` | Limite de séries aplicado no registry real |
| `HealthMetricsIntegrationTest` | Health check e a métrica correspondente |
