# Spring Boot Scaling Lab

A hands-on performance-testing project that measures how a CPU-intensive
Spring Boot endpoint behaves under Docker CPU limits. It uses **k6** to
generate HTTP load, **Prometheus** to collect application metrics, and
**Grafana** to visualize CPU usage, JVM heap memory, request throughput,
and average response latency.

> **Important:** This is a learning lab, not a production-capacity
> benchmark. k6 and Docker run on the same machine, which affects
> results. Repeat tests and compare multiple runs before drawing firm
> conclusions.

## Table of contents

1.  [Project goals](#project-goals)
2.  [Architecture](#architecture)
3.  [Prerequisites](#prerequisites)
4.  [Project structure](#project-structure)
5.  [Create the Spring Boot
    application](#create-the-spring-boot-application)
6.  [Configure Actuator and Prometheus
    metrics](#configure-actuator-and-prometheus-metrics)
7.  [Build and test locally](#build-and-test-locally)
8.  [Containerize the application](#containerize-the-application)
9.  [Run the application with a CPU
    limit](#run-the-application-with-a-cpu-limit)
10. [Install and run k6](#install-and-run-k6)
11. [Run a benchmark and save the
    results](#run-a-benchmark-and-save-the-results)
12. [Set up Prometheus](#set-up-prometheus)
13. [Set up Grafana](#set-up-grafana)
14. [Create the dashboard panels](#create-the-dashboard-panels)
15. [Compare 1, 2, and 4 CPUs](#compare-1-2-and-4-cpus)
16. [Save dashboard and results](#save-dashboard-and-results)
17. [Troubleshooting](#troubleshooting)
18. [Key learnings and project
    takeaways](#key-learnings-and-project-takeaways)
19. [Interpreting results](#interpreting-results)
20. [Useful commands](#useful-commands)

## Project goals

-   Build a CPU-intensive HTTP endpoint in Spring Boot.
-   Limit the application container to 1, 2, or 4 CPUs while keeping
    memory fixed.
-   Send the same workload to each configuration with k6.
-   Measure throughput, average latency, p95 latency, and failed
    requests.
-   Collect Spring Boot metrics using Actuator and Prometheus.
-   Visualize metrics in Grafana.
-   Store benchmark summaries and compare configurations.

## Architecture

``` text
                    ┌────────────────────────────┐
                    │       k6 load test         │
                    │  25 VUs for 30 seconds     │
                    └──────────────┬─────────────┘
                                   │ HTTP
                                   ▼
                    ┌────────────────────────────┐
                    │  Spring Boot application   │
                    │  /api/benchmark            │
                    │  Docker CPU/memory limits  │
                    └──────────────┬─────────────┘
                                   │ /actuator/prometheus
                                   ▼
                    ┌────────────────────────────┐
                    │         Prometheus         │
                    │   scrape interval: 5 sec   │
                    └──────────────┬─────────────┘
                                   │ PromQL
                                   ▼
                    ┌────────────────────────────┐
                    │          Grafana           │
                    │  CPU / heap / RPS / latency│
                    └────────────────────────────┘
```

Prometheus and Grafana run in Docker Compose. The Spring Boot
application runs as a separate Docker container and publishes port
`8080` to the host. Prometheus reaches it using
`host.docker.internal:8080`, which is supported by Docker Desktop on
Windows and macOS.

## Prerequisites

The original development environment was Windows 11, Docker Desktop,
Java 21, Maven, and k6. The steps below use PowerShell. Linux/macOS
users can adapt the shell commands and Docker Desktop host networking
address as appropriate.

Install:

-   **Java 21 JDK** (Temurin/OpenJDK 21 recommended)
-   **Docker Desktop** with the Linux container engine enabled
-   **Git** (optional, for version control)
-   **k6** (native Windows installation is used in the examples)
-   An editor such as IntelliJ IDEA or VS Code

Verify installations:

``` powershell
java -version
docker --version
docker compose version
k6 version
```

If you use the Maven Wrapper included in the project, a separate Maven
installation is not normally required to build the project. The
Dockerfile below expects the Maven Wrapper files to exist.

Check Docker is running:

``` powershell
docker info
```

If this fails, open Docker Desktop and wait until the engine is running.

## Project structure

The project should have the following structure. The Spring Boot Java
files are under `src/main/java`; their exact package path must match the
`package` declaration.

``` text
scaling-lab/
├── .mvn/
│   └── wrapper/
├── mvnw
├── mvnw.cmd
├── pom.xml
├── Dockerfile
├── docker-compose.yml
├── load-test.js
├── monitoring/
│   └── prometheus.yml
├── results/
│   ├── benchmark-comparison.csv
│   ├── 1cpu-summary.json
│   ├── 2cpu-summary.json
│   └── 4cpu-summary.json
└── src/
    └── main/
        ├── java/
        │   └── com/
        │       └── rishi/
        │           └── scalinglab/
        │               ├── ScalingLabApplication.java
        │               └── BenchmarkController.java
        └── resources/
            └── application.properties
```

Some result files are created only after following the save-results
steps below. Do not commit generated files until you have checked that
they contain the intended data.

## Create the Spring Boot application

### 1. Generate the project

Create a Spring Boot Maven project with:

-   Group: `com.rishi`
-   Artifact: `scaling-lab`
-   Name: `scaling-lab`
-   Package: `com.rishi.scalinglab`
-   Java: `21`

Use a Spring Boot version supported by your environment. The original
project used Spring Boot 4.x. If you choose another version, use
dependency versions managed by that Spring Boot release rather than
manually pinning arbitrary Micrometer versions.

Required dependencies: - Spring Web - Spring Boot Actuator - Prometheus
registry for Micrometer

If you already have the working project, keep its existing package,
artifact, and version consistent with your `Dockerfile` copy path.

### 2. Main application class

File: `src/main/java/com/rishi/scalinglab/ScalingLabApplication.java`

``` java
package com.rishi.scalinglab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ScalingLabApplication {
    public static void main(String[] args) {
        SpringApplication.run(ScalingLabApplication.class, args);
    }
}
```

### 3. Benchmark controller

File: `src/main/java/com/rishi/scalinglab/BenchmarkController.java`

The endpoint must do CPU work so CPU limits can affect throughput and
latency. This is a minimal example compatible with the test URL used in
this project:

``` java
package com.rishi.scalinglab;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BenchmarkController {

    @GetMapping("/api/benchmark")
    public BenchmarkResponse benchmark(
            @RequestParam(defaultValue = "100000") int iterations) {

        double result = 0.0;

        for (int i = 1; i <= iterations; i++) {
            result += Math.sqrt(i) * Math.sin(i);
        }

        return new BenchmarkResponse(iterations, result);
    }

    public record BenchmarkResponse(int iterations, double result) {}
}
```

This is intentionally CPU-intensive and is not representative of every
real application. Do not expose an unrestricted CPU-work endpoint on a
public production service. For a lab, keep it local and use reasonable
iteration values.

## Configure Actuator and Prometheus metrics

### 1. Add dependencies

In `pom.xml`, ensure these dependencies are present inside
`<dependencies>`. Keep the Spring Boot parent/BOM responsible for
compatible versions:

``` xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>

<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

If the project was generated with Spring Web, keep its existing web
dependency as well.

### 2. Configure application properties

File: `src/main/resources/application.properties`

``` properties
management.endpoints.web.exposure.include=health,prometheus
```

Only expose the endpoints needed for this local lab. Do not expose
sensitive management endpoints publicly.

### 3. Build the application

From the project root:

``` powershell
.\mvnw.cmd clean package -DskipTests
```

If using a system Maven installation instead:

``` powershell
mvn clean package -DskipTests
```

Expected outcome: `BUILD SUCCESS` and a JAR under `target/`.

### 4. Test locally

Start the application using the JAR generated in `target` (adjust the
JAR name if your version differs):

``` powershell
java -jar target/scaling-lab-0.0.1-SNAPSHOT.jar
```

In a second PowerShell window:

``` powershell
Invoke-RestMethod -UseBasicParsing "http://localhost:8080/actuator/health"
```

Expected JSON includes `"status":"UP"`.

Check the Prometheus endpoint:

``` powershell
(Invoke-WebRequest -UseBasicParsing "http://localhost:8080/actuator/prometheus").StatusCode
```

Expected status code: `200`.

Test the benchmark endpoint:

``` powershell
Invoke-RestMethod -UseBasicParsing "http://localhost:8080/api/benchmark?iterations=100000"
```

Stop the local Java process with `Ctrl+C` before starting a Docker
container that also uses port `8080`.

## Containerize the application

### 1. Create the Dockerfile

File: `Dockerfile` in the project root.

``` dockerfile
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY src/ src/

RUN chmod +x mvnw && ./mvnw -B clean package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=build /app/target/scaling-lab-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-jar", "app.jar"]
```

**Important:** The JAR filename in the `COPY --from=build` line must
match the artifact name and version produced by Maven. If the output JAR
has a different name, update that line or use a stable JAR name in your
build.

The `MaxRAMPercentage` option limits the JVM heap target relative to the
memory available to the JVM. Docker still enforces the container's
memory limit. This does not set a CPU limit; CPU limits are applied when
running the container.

### 2. Build the image

``` powershell
docker build -t scaling-lab:latest .
```

Verify the image exists:

``` powershell
docker image ls scaling-lab
```

### 3. Run a 2-CPU container

Ensure port `8080` is free and there is no old application container
still publishing it. Then:

``` powershell
docker run -d --name scaling-lab-monitoring --cpus="2.0" --memory="768m" -p 8080:8080 scaling-lab:latest
```

Check status:

``` powershell
docker ps
```

Check limits:

``` powershell
docker inspect scaling-lab-monitoring --format "Status={{.State.Status}} CPU={{.HostConfig.NanoCpus}} Memory={{.HostConfig.Memory}}"
```

For this configuration, expected values are `CPU=2000000000` and
`Memory=805306368`.

Check logs if the app is not ready:

``` powershell
docker logs --tail 100 scaling-lab-monitoring
```

Wait for Spring Boot to finish starting, then check health and
Prometheus as shown earlier.

## Install and run k6

Install k6 from the official Grafana k6 installation instructions for
your operating system. Verify it:

``` powershell
k6 version
```

### Create the load-test script

File: `load-test.js` in the project root.

``` javascript
import http from 'k6/http';
import { check } from 'k6';

export const options = {
  vus: 25,
  duration: '30s',
  thresholds: {
    http_req_failed: ['rate<0.01'],
  },
};

export default function () {
  const response = http.get(
    'http://localhost:8080/api/benchmark?iterations=100000'
  );

  check(response, {
    'status is 200': (r) => r.status === 200,
  });
}
```

This test keeps 25 virtual users active for 30 seconds. Each iteration
sends a GET request to the CPU-intensive endpoint. The threshold
requires the failed HTTP request rate to stay below 1%.

The test is a closed-loop workload: each virtual user sends another
request after the prior iteration completes. Throughput therefore
changes with response time.

### Run a quick test

With the application running:

``` powershell
k6 run load-test.js
```

Look for: - `http_reqs`: completed HTTP requests and requests per
second - `http_req_duration`: average, median, p90, and p95 latency -
`http_req_failed`: failed request ratio - `checks_succeeded`: successful
status-code checks

## Run a benchmark and save the results

### 1. Create a results directory

``` powershell
New-Item -ItemType Directory -Force results
```

### 2. Save a k6 summary for each run

The following commands **run the tests again** and save the summary from
each new run:

``` powershell
k6 run --summary-export results/1cpu-summary.json load-test.js
k6 run --summary-export results/2cpu-summary.json load-test.js
k6 run --summary-export results/4cpu-summary.json load-test.js
```

Run each command only when the application container has the matching
CPU limit. These JSON files are summaries, not full per-request
time-series output. For detailed time-series samples, use k6's supported
output options, such as CSV, and check the current k6 documentation for
exact output format and options.

### 3. Record the results already observed

The following PowerShell command creates
`results/benchmark-comparison.csv` using the three results recorded in
this project session. These are the observed values from those
particular runs, not guaranteed repeatable performance numbers.

``` powershell
@'
CPU,Memory_MiB,VUs,Duration_s,Requests,Throughput_req_s,Avg_ms,P95_ms,Failed_pct
1,768,25,30,2292,75.949765,327.4,1090,0
2,768,25,30,10378,345.269511,71.78,108.2,0
4,768,25,30,15466,514.520881,48.16,95.38,0
'@ | Set-Content -Encoding utf8 results/benchmark-comparison.csv
```

View the CSV:

``` powershell
Get-Content results/benchmark-comparison.csv
```

Or import it as a PowerShell table:

``` powershell
Import-Csv results/benchmark-comparison.csv | Format-Table -AutoSize
```

These results were collected across separate runs. To make a stronger
comparison, perform multiple runs per CPU configuration, alternate the
order when practical, and compare the median results.

## Set up Prometheus

### 1. Create the monitoring folder

``` powershell
New-Item -ItemType Directory -Force monitoring
```

### 2. Create Prometheus configuration

File: `monitoring/prometheus.yml`

``` yaml
global:
  scrape_interval: 5s

scrape_configs:
  - job_name: "spring-boot"
    metrics_path: "/actuator/prometheus"
    static_configs:
      - targets: ["host.docker.internal:8080"]
```

The target is the host-published Spring Boot port. This setup assumes
Docker Desktop on Windows or macOS. If using native Linux Docker Engine,
`host.docker.internal` may need explicit host-gateway configuration, or
you can put the app and monitoring containers on the same Docker network
and target the app by service/container DNS name.

### 3. Create Docker Compose configuration

File: `docker-compose.yml` in the project root.

``` yaml
services:
  prometheus:
    image: prom/prometheus:latest
    container_name: scaling-lab-prometheus
    command:
      - --config.file=/etc/prometheus/prometheus.yml
      - --storage.tsdb.path=/prometheus
      - --storage.tsdb.retention.time=3d
    ports:
      - "9090:9090"
    volumes:
      - ./monitoring/prometheus.yml:/etc/prometheus/prometheus.yml:ro
    restart: unless-stopped
    mem_limit: 384m

  grafana:
    image: grafana/grafana:latest
    container_name: scaling-lab-grafana
    ports:
      - "3000:3000"
    environment:
      GF_SECURITY_ADMIN_USER: admin
      GF_SECURITY_ADMIN_PASSWORD: labadmin123
      GF_USERS_ALLOW_SIGN_UP: "false"
    depends_on:
      - prometheus
    restart: unless-stopped
    mem_limit: 512m
```

This Compose file starts Prometheus and Grafana only; the Spring Boot
app is run separately. The default Grafana password is intended only for
a local lab. Change it for any environment beyond a disposable local
setup, and never commit real credentials.

### 4. Validate the Compose file

From the project root:

``` powershell
docker compose config
```

Resolve any YAML or path errors before starting services.

### 5. Start Prometheus and Grafana

``` powershell
docker compose up -d
```

Check:

``` powershell
docker compose ps
```

Expected containers: - `scaling-lab-prometheus` on host port `9090` -
`scaling-lab-grafana` on host port `3000`

View logs if necessary:

``` powershell
docker compose logs --tail 100 prometheus
docker compose logs --tail 100 grafana
```

### 6. Verify Prometheus is scraping Spring Boot

Open `http://localhost:9090`.

Go to **Status → Targets** (the exact menu label may differ by
Prometheus version). The `spring-boot` target should be `UP`.

You can also run the PromQL query:

``` promql
up
```

A value of `1` means the scrape is successful. A value of `0` means the
target exists but the scrape failed. No result usually means no matching
target or no data.

If the target is down, confirm the app health endpoint and
`http://localhost:8080/actuator/prometheus` both work from the host.
Then inspect Prometheus logs and the `prometheus.yml` target.

## Set up Grafana

### 1. Open Grafana

Go to `http://localhost:3000`.

For the sample Compose configuration, sign in with: - Username:
`admin` - Password: `labadmin123`

If Grafana asks you to change the password, follow the prompt.

### 2. Add the Prometheus data source

1.  Open **Connections → Data sources**.

2.  Select **Add data source**.

3.  Choose **Prometheus**.

4.  Set the Prometheus server URL to:

    ``` text
    http://prometheus:9090
    ```

5.  Click **Save & test**.

Use `http://prometheus:9090`, not `http://localhost:9090`: Grafana runs
inside its own container, and `prometheus` is the Compose service name
on the shared Compose network.

### 3. Create a dashboard

1.  Open **Dashboards**.
2.  Create a new dashboard.
3.  Add a visualization/panel.
4.  Select the `prometheus` data source.
5.  Use **Code** mode to enter PromQL.
6.  Set the title and units for each panel.
7.  Apply the panel and save the dashboard.

Menu names may vary slightly between Grafana versions.

## Create the dashboard panels

These queries match the metric names observed in this project.
Prometheus metric names can vary with Spring Boot/Micrometer versions
and configuration; if a query returns no data, inspect the available
metric names in Prometheus before changing the query.

### Panel 1: Spring Boot Process CPU Usage (%)

PromQL:

``` promql
process_cpu_usage * 100
```

Title: `Spring Boot Process CPU Usage (%)`

This is process CPU usage expressed as a percentage of one CPU's
capacity. It is not the same as total container CPU usage. On a
multi-core container, process CPU usage may exceed 100% if the process
uses more than one core.

### Panel 2: JVM Heap Memory (MB)

PromQL:

``` promql
sum(jvm_memory_used_bytes{area="heap"}) / 1024 / 1024
```

Title: `JVM Heap Memory (MB)`

Unit: megabytes (MB), if available in the Grafana unit selector. This
sums JVM heap memory used across the heap memory pools.

### Panel 3: HTTP Requests Per Second

PromQL:

``` promql
sum(rate(http_server_requests_seconds_count{uri="/api/benchmark"}[1m]))
```

Title: `HTTP Requests Per Second`

This estimates the average rate of completed requests over a one-minute
window. A short test or a quiet app may show low values; run k6 to
generate traffic.

### Panel 4: HTTP Average Response Latency (ms)

The project did not expose the histogram bucket metric needed by the
original p95 query, so use the average latency query below unless
histogram buckets are explicitly enabled.

PromQL:

``` promql
1000 *
sum(rate(http_server_requests_seconds_sum{uri="/api/benchmark"}[1m]))
/
sum(rate(http_server_requests_seconds_count{uri="/api/benchmark"}[1m]))
```

Title: `HTTP Average Response Latency (ms)`

Unit: milliseconds (ms).

This is **average** latency, not p95. The query divides the increase in
cumulative request duration by the increase in completed request count
over the time window. If the denominator is zero or the metric names
differ, the panel may show no data.

### Optional: Actual p95 latency

The histogram query below works only if the corresponding `_bucket`
metrics are available. First verify in Prometheus that
`http_server_requests_seconds_bucket` exists.

``` promql
1000 * histogram_quantile(
  0.95,
  sum by (le) (
    rate(http_server_requests_seconds_bucket{uri="/api/benchmark"}[1m])
  )
)
```

If the buckets do not exist, enable histogram publication for the
relevant HTTP request metric using the configuration supported by your
Spring Boot/Micrometer version, rebuild the image, and verify that the
bucket series appear in `/actuator/prometheus`. Do not label an
average-latency graph as p95.

## Compare 1, 2, and 4 CPUs

The key to a meaningful comparison is to keep the following unchanged: -
Same Docker image/build - Same memory limit (`768m`) - Same endpoint and
iteration count - Same k6 script (`25` VUs, `30s`) - Same host machine
and background workload, as far as practical - Same monitoring
configuration

Only change the application container CPU limit.

### 1. Stop and remove the previous application container

Use the actual container name that currently exists. For example:

``` powershell
docker rm -f scaling-lab-monitoring
```

or:

``` powershell
docker rm -f scaling-lab-1cpu
```

or:

``` powershell
docker rm -f scaling-lab-4cpu
```

Do not remove Prometheus or Grafana. They run independently through
Docker Compose.

If unsure which app container exists:

``` powershell
docker ps -a
```

### 2. Run the 1-CPU configuration

``` powershell
docker run -d --name scaling-lab-1cpu --cpus="1.0" --memory="768m" -p 8080:8080 scaling-lab:latest
```

Verify the limit:

``` powershell
docker inspect scaling-lab-1cpu --format "Status={{.State.Status}} CPU={{.HostConfig.NanoCpus}} Memory={{.HostConfig.Memory}}"
```

Expected CPU quota: `1000000000`; expected memory: `805306368`.

Wait for the app to start, then check:

``` powershell
Invoke-RestMethod -UseBasicParsing http://localhost:8080/actuator/health
```

Run and save the summary:

``` powershell
k6 run --summary-export results/1cpu-summary.json load-test.js
```

### 3. Run the 2-CPU configuration

Remove the previous application container first. Then:

``` powershell
docker run -d --name scaling-lab-2cpu --cpus="2.0" --memory="768m" -p 8080:8080 scaling-lab:latest
```

Verify:

``` powershell
docker inspect scaling-lab-2cpu --format "Status={{.State.Status}} CPU={{.HostConfig.NanoCpus}} Memory={{.HostConfig.Memory}}"
```

Expected CPU quota: `2000000000`; expected memory: `805306368`.

Health check:

``` powershell
Invoke-RestMethod -UseBasicParsing http://localhost:8080/actuator/health
```

Run:

``` powershell
k6 run --summary-export results/2cpu-summary.json load-test.js
```

### 4. Run the 4-CPU configuration

Remove the previous app container first. Then:

``` powershell
docker run -d --name scaling-lab-4cpu --cpus="4.0" --memory="768m" -p 8080:8080 scaling-lab:latest
```

Verify:

``` powershell
docker inspect scaling-lab-4cpu --format "Status={{.State.Status}} CPU={{.HostConfig.NanoCpus}} Memory={{.HostConfig.Memory}}"
```

Expected CPU quota: `4000000000`; expected memory: `805306368`.

Health check:

``` powershell
Invoke-RestMethod -UseBasicParsing http://localhost:8080/actuator/health
```

Run:

``` powershell
k6 run --summary-export results/4cpu-summary.json load-test.js
```

### 5. Watch Grafana during each test

Open `http://localhost:3000`, select the scaling dashboard, and set the
time range to **Last 5 minutes** (or a suitable recent range). Refresh
or enable dashboard auto-refresh.

During k6, observe: - CPU usage - JVM heap memory - HTTP requests per
second - Average response latency

Prometheus scrapes every 5 seconds, so brief peaks can be missed. k6's
final summary is the primary source for the run's aggregate latency and
throughput.

## Save dashboard and results

### Save the Grafana dashboard

1.  Open the dashboard.
2.  Click **Save dashboard** and confirm.
3.  If available, use **Share → Export** to export the dashboard JSON.
4.  Save the export in the project,
    e.g. `results/scaling-dashboard.json`.

### Save a reproducible record

Keep: - `load-test.js` - `monitoring/prometheus.yml` -
`docker-compose.yml` - `Dockerfile` -
`results/benchmark-comparison.csv` - `results/*-summary.json` -
`results/scaling-dashboard.json` (if exported)

For a clean Git history, don't commit enormous raw output files or
secrets. Consider adding a `.gitignore` for temporary files and local
IDE settings.

## Troubleshooting

### Docker says a container name is already in use

List all containers:

``` powershell
docker ps -a
```

Remove only the old **application** container that conflicts with the
intended name:

``` powershell
docker rm -f scaling-lab-4cpu
```

Then recreate it. Do not remove `scaling-lab-prometheus` or
`scaling-lab-grafana` while changing CPU configurations.

### Port 8080 is already allocated

Find which container uses it:

``` powershell
docker ps
```

Stop/remove the old application container, then start the desired
configuration. Only one of these single-container tests can publish host
port `8080` at a time.

### Health or Prometheus endpoint returns 404

A running server can still return 404 if the active container uses an
older image without Actuator dependencies/configuration. Check the
active image and logs:

``` powershell
docker ps -a
docker inspect scaling-lab-4cpu --format "Container image={{.Image}}"
docker image inspect scaling-lab:latest --format "Image ID={{.Id}} Created={{.Created}}"
docker logs --tail 100 scaling-lab-4cpu
```

Rebuild the latest image from the project root:

``` powershell
docker build -t scaling-lab:latest .
```

Then remove and recreate the application container from that image.
Confirm both endpoints:

``` powershell
Invoke-RestMethod -UseBasicParsing http://localhost:8080/actuator/health
(Invoke-WebRequest -UseBasicParsing http://localhost:8080/actuator/prometheus).StatusCode
```

Expected health status is `UP`, and Prometheus status code is `200`.

### Prometheus target is DOWN

1.  Confirm the app responds on the host at port `8080`.

2.  Confirm `monitoring/prometheus.yml` uses
    `host.docker.internal:8080`.

3.  Inspect Prometheus logs:

    ``` powershell
    docker compose logs --tail 100 prometheus
    ```

4.  Open `http://localhost:9090/targets` and inspect the scrape error.

5.  Check that the YAML indentation is correct and the file was saved as
    `prometheus.yml`, not `prometheus.yml.txt`.

### Grafana cannot connect to Prometheus

In the data source configuration, use:

``` text
http://prometheus:9090
```

Do not use `localhost:9090` from inside the Grafana container. Verify
both Compose services are running:

``` powershell
docker compose ps
```

### Grafana panel says "No data"

-   Confirm the Prometheus target is `UP`.
-   Run the metric name directly in Prometheus first.
-   Confirm the app has received requests.
-   Widen the Grafana time range.
-   Check metric names in `/actuator/prometheus`; names can vary by
    framework/version.
-   The p95 query requires histogram buckets. If `_bucket` series are
    absent, use average latency or configure histogram publication.

### k6 reports connection refused

Wait until the app starts:

``` powershell
docker logs --tail 100 scaling-lab-4cpu
Invoke-RestMethod -UseBasicParsing http://localhost:8080/actuator/health
```

Use the actual name of the active application container in the
`docker logs` command.

### Maven build fails

-   Check Java version: `java -version`.
-   Make sure `pom.xml`, `mvnw`, `.mvn/`, and `src/` exist.
-   Run from the project root.
-   Confirm the Dockerfile's JAR path matches the artifact generated
    under `target/`.
-   Read the first Maven `ERROR` message rather than relying only on the
    final `BUILD FAILURE` line.

### Results differ significantly between runs

This can happen because the laptop is also running Docker, Prometheus,
Grafana, k6, the IDE, and other applications. Warm-up, background
processes, JVM behavior, CPU throttling, and test order can affect
results. Run several trials and compare medians.

## Key learnings and project takeaways

This section answers the most important question about the project:
**"What is the point of building this, and what did the results teach
us?"**

The goal was not simply to run a Spring Boot application inside Docker.
The goal was to create a small, observable system, apply a controlled
workload, change one resource limit at a time, and use measurements to
understand how the application behaved.

### 1. Scaling is a measurable engineering decision

We ran the same CPU-intensive endpoint with 25 virtual users for 30
seconds while changing the container CPU limit from 1 to 2 to 4 CPUs.
The recorded throughput increased across these runs, while average and
p95 latency decreased.

  --------------------------------------------------------------------------
       CPU limit     Throughput        Average    p95 latency         Failed
                                       latency                      requests
  -------------- -------------- -------------- -------------- --------------
           1 CPU    75.95 req/s      327.40 ms       1,090 ms             0%

          2 CPUs   345.27 req/s       71.78 ms      108.20 ms             0%

          4 CPUs   514.52 req/s       48.16 ms       95.38 ms             0%
  --------------------------------------------------------------------------

**What this tells us:** Under this particular synthetic workload,
allowing more CPU capacity coincided with higher throughput and lower
latency. The application was able to complete more work in the fixed
test window.

**What it does not prove:** It does not prove that every application
will scale this way, that doubling CPUs will double throughput, or that
these numbers represent production capacity. These are single-run
observations from a laptop where k6, Docker, Prometheus, Grafana, and
other programs share resources. Repeat tests and compare medians before
making strong claims.

### 2. Throughput and latency must be considered together

Throughput answers, "How much work completed per second?" Latency
answers, "How long did a request take?" Neither metric is sufficient on
its own.

For example, a service might achieve high throughput while individual
requests become slow, or it might respond quickly to a very small number
of requests. In this experiment, both throughput and latency improved in
the higher-CPU runs. That is a more useful signal than looking at
throughput alone.

-   **Throughput:** k6's `http_reqs` rate, measured in requests per
    second.
-   **Average latency:** the average duration across completed requests.
-   **p95 latency:** the latency below which 95% of measured requests
    completed; 5% took longer.
-   **Failure rate:** whether requests failed during the test.

### 3. CPU limits are constraints, not extra physical resources

Docker's `--cpus` setting limits how much CPU time the container can
consume. It does not add physical cores to the laptop. The host still
has a finite CPU budget shared among all running processes.

This lab helps demonstrate the difference between: - the laptop's
physical/logical CPU capacity, - the CPU quota applied to one
container, - CPU usage reported for the Java process, and - the overall
resource consumption of the host.

The `process_cpu_usage` metric is process-level CPU usage expressed
relative to one CPU's capacity. It should not be confused with the
host's total CPU percentage or a complete measurement of container CPU
usage.

### 4. Observability helps explain performance, not just report it

The monitoring stack gives each tool a distinct job:

-   **Spring Boot Actuator and Micrometer** expose application and JVM
    measurements.
-   **Prometheus** periodically scrapes and stores time-series metrics.
-   **Grafana** turns those metrics into charts that can be inspected
    during a load test.
-   **k6** generates traffic and reports request-level performance
    statistics.

The dashboard lets us correlate load with CPU usage, JVM heap memory,
request throughput, and latency. It helps us ask better questions, such
as: Did CPU usage rise when traffic increased? Did memory continue to
grow, or did garbage collection reduce it? Did latency increase as the
service became busier?

Metrics are evidence for investigation, not automatic explanations. For
example, heap memory changing over time does not by itself prove a
memory leak; JVM garbage collection can cause normal rises and drops.

### 5. Metrics must be interpreted correctly

A metric's name and units matter. This project uses the following
distinctions:

-   `process_cpu_usage * 100` is Java process CPU usage as a percentage
    of one CPU's capacity.
-   JVM heap usage is the amount of heap memory currently reported as
    used; it is not the same as total container memory.
-   Request rate is calculated from the rate of increase in completed
    request counts.
-   The current latency panel calculates **average** request latency
    using the change in cumulative request duration divided by the
    change in request count.

The initial p95 PromQL query returned no data because histogram bucket
series were not exposed in the metrics available to this setup. The
panel was changed to show average latency. **The current Grafana latency
panel is not p95.** To calculate p95 in Prometheus, histogram bucket
metrics must be enabled and verified first. k6 independently reports p95
in its test summary.

This is a useful engineering lesson: when a graph is empty, inspect the
available metrics and confirm the query's assumptions rather than
labeling a different measurement as the desired one.

### 6. A successful HTTP status is not the same as good performance

All three recorded runs reported a 0% failed-request rate and successful
HTTP 200 checks. That tells us the endpoint remained responsive to the
test requests, but it does not mean all performance goals were met or
that the application is production-ready.

A service can return HTTP 200 while being too slow for users. Production
readiness also depends on workload realism, security, resource limits,
error handling, sustained tests, and service-level objectives.

### 7. Fair experiments require controlled variables

To make CPU comparisons meaningful, keep the image, endpoint, number of
virtual users, test duration, iteration count, memory limit, and machine
as consistent as possible. Change only the CPU limit for each run.

This project also demonstrates why a benchmark should be repeated.
Results can vary because of JVM warm-up, background tasks, Docker
resource contention, monitoring overhead, test order, and other
processes on the laptop. A stronger experiment would: 1. Build one image
and use that same image for all configurations. 2. Verify the actual CPU
and memory limits with `docker inspect`. 3. Wait for the application
health check to report `UP`. 4. Run several trials per CPU
configuration. 5. Alternate the order of configurations when practical.
6. Compare median throughput and latency, and record failures. 7. Keep
the raw summaries with the comparison table.

### 8. Containerization makes resource experiments repeatable

Docker lets us run the same application image with different CPU quotas
and a fixed memory limit. This reduces differences between deployments
and makes it easier to test how a resource constraint affects behavior.

A container limit is not the same as a scaling strategy by itself. This
project tests **vertical resource scaling**---giving one application
container a larger CPU allowance. It does not implement horizontal
scaling with multiple application replicas or a load balancer.

### 9. What would we do next in a real system?

This lab is a starting point for deeper performance engineering. Useful
next steps include:

-   Repeat each test several times and report medians and variation.
-   Enable and verify histogram buckets to plot p95/p99 latency in
    Prometheus.
-   Record container CPU throttling and container memory metrics, not
    only JVM metrics.
-   Test multiple workload shapes, including ramp-up, sustained load,
    and spike tests.
-   Increase virtual users gradually to find where throughput stops
    increasing or latency rises sharply.
-   Use a separate load-generator machine so k6 does not compete with
    the application for host resources.
-   Test a realistic endpoint and workload, not only a synthetic CPU
    loop.
-   Add explicit service-level objectives, such as a target p95 latency
    and an acceptable error rate.
-   If appropriate, compare one larger instance against multiple smaller
    replicas behind a load balancer.

### 10. How to explain the project in an interview

**Short answer:**

> "I built a Spring Boot performance-testing lab to understand how CPU
> allocation affects application performance. I containerized a
> CPU-intensive endpoint, used Docker to test it with 1, 2, and 4 CPU
> limits, generated a consistent load with k6, and collected application
> metrics with Prometheus and Grafana. In my recorded runs, throughput
> increased from about 76 requests per second at 1 CPU to about 515
> requests per second at 4 CPUs, while p95 latency decreased from about
> 1.09 seconds to about 95 milliseconds. The experiment taught me to
> evaluate throughput, latency, errors, and resource usage together, and
> to treat benchmark results carefully because they vary with the
> environment. These are lab measurements, not production-capacity
> guarantees."

**The main takeaway:** The point of the project is to learn how to form
a performance hypothesis, run a controlled test, observe the system,
interpret evidence, and identify what to measure next---not simply to
show that more CPUs are always better.

## Interpreting results

The three most important values are:

-   **Throughput (req/s):** how many requests k6 completed per second.
-   **Average latency:** the average response time of completed
    requests.
-   **p95 latency:** the 95th percentile of response times reported by
    k6; 95% of measured requests were no slower than this value.
-   **Failed request rate:** fraction of HTTP requests that failed
    according to k6's HTTP request metrics/checks.

The CPU scaling factor is not guaranteed to be linear. More CPUs can
improve throughput until another bottleneck becomes dominant, such as
memory, locks, request generation, network, or a single-threaded part of
the application.

### Results recorded during this project session

These are the results from the particular runs recorded during the
project walkthrough:

  -----------------------------------------------------------------------------------------
      CPU   Memory    VUs   Duration   Requests   Throughput   Average       p95   Failures
    limit    limit                                             latency   latency 
  ------- -------- ------ ---------- ---------- ------------ --------- --------- ----------
    1 CPU  768 MiB     25       30 s      2,292  75.95 req/s 327.40 ms  1,090 ms         0%

    2 CPU  768 MiB     25       30 s     10,378 345.27 req/s  71.78 ms 108.20 ms         0%

    4 CPU  768 MiB     25       30 s     15,466 514.52 req/s  48.16 ms  95.38 ms         0%
  -----------------------------------------------------------------------------------------

These values are useful as a demonstration, but they are single-run
measurements and should not be presented as guaranteed capacity. Re-run
all configurations from the same image and compare several trials for a
more reliable conclusion.

## Useful commands

``` powershell
# Show running containers
docker ps

# Show all containers, including stopped ones
docker ps -a

# Show application logs
docker logs --tail 100 scaling-lab-4cpu

# Check CPU/memory limits
docker inspect scaling-lab-4cpu --format "Status={{.State.Status}} CPU={{.HostConfig.NanoCpus}} Memory={{.HostConfig.Memory}}"

# Check application health
Invoke-RestMethod -UseBasicParsing http://localhost:8080/actuator/health

# Check Prometheus endpoint status
(Invoke-WebRequest -UseBasicParsing http://localhost:8080/actuator/prometheus).StatusCode

# Validate and start monitoring services
docker compose config
docker compose up -d
docker compose ps

# Stop monitoring services (does not delete containers)
docker compose stop

# Run k6
k6 run load-test.js

# Run k6 and save a summary
k6 run --summary-export results/4cpu-summary.json load-test.js
```

## License and security note

Choose a license before publishing the repository. The sample benchmark
endpoint is intentionally CPU-intensive and should be used only in a
controlled local environment. Do not expose the endpoint, Actuator
metrics, Prometheus, or Grafana to the public internet without
appropriate authentication, network restrictions, and security
configuration.
