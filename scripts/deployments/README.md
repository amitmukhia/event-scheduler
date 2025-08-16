# Shared Binary Deployment Architecture

## Overview

This event scheduler uses a **shared binary approach** where the same JAR file is deployed across multiple environments with different configurations. Each deployment is isolated by:

- Separate databases
- Separate Kafka topic prefixes  
- Separate Redis databases
- Customer-specific configuration
- Independent scaling and monitoring

## Deployment Examples

### 1. Ocean Event Scheduler

Environment Variables:
```bash
# Application Identity
DEPLOYMENT_NAME=event-scheduler-ocean
CUSTOMER_NAME=ocean
ENVIRONMENT=production
SERVER_PORT=8080

# Database Configuration (Ocean-specific DB)
DATABASE_URL=jdbc:postgresql://ocean-db.example.com:5432/ocean_events_db
DATABASE_USERNAME=ocean_user
DATABASE_PASSWORD=ocean_secure_password
DB_POOL_MAX_SIZE=50
DB_POOL_MIN_IDLE=15

# Kafka Configuration (Ocean-specific topics)
KAFKA_BOOTSTRAP_SERVERS=kafka-ocean.example.com:9092
KAFKA_TOPIC_SUBMISSION=ocean.event.submissions
KAFKA_TOPIC_SCHEDULED=ocean.event.scheduled
KAFKA_TOPIC_READY=ocean.event.ready
KAFKA_TOPIC_TIMESTAMP=ocean.timestamp.processing
KAFKA_TOPIC_PROCESSING=ocean.process-kafka-message
KAFKA_TOPIC_DLQ=ocean.event.dlq
KAFKA_CONSUMER_GROUP=ocean-event-processors
KAFKA_STREAMS_APP_ID=ocean-event-scheduler-streams

# Redis Configuration (Ocean-specific database)
REDIS_HOST=redis-ocean.example.com
REDIS_PORT=6379
REDIS_DATABASE=1
REDIS_PASSWORD=ocean_redis_password

# Performance Tuning for Ocean (High Volume)
BATCH_SIZE=1000
EXECUTION_INTERVAL=3000
KAFKA_CONSUMER_CONCURRENCY=15
CUSTOMER_MAX_PARALLEL=20

# Logging
LOG_LEVEL=INFO
LOG_FILE=logs/ocean-event-scheduler.log
```

### 2. Air Event Scheduler

Environment Variables:
```bash
# Application Identity
DEPLOYMENT_NAME=event-scheduler-air
CUSTOMER_NAME=air
ENVIRONMENT=production
SERVER_PORT=8081

# Database Configuration (Air-specific DB)
DATABASE_URL=jdbc:postgresql://air-db.example.com:5432/air_events_db
DATABASE_USERNAME=air_user
DATABASE_PASSWORD=air_secure_password
DB_POOL_MAX_SIZE=40
DB_POOL_MIN_IDLE=12

# Kafka Configuration (Air-specific topics)
KAFKA_BOOTSTRAP_SERVERS=kafka-air.example.com:9092
KAFKA_TOPIC_SUBMISSION=air.event.submissions
KAFKA_TOPIC_SCHEDULED=air.event.scheduled
KAFKA_TOPIC_READY=air.event.ready
KAFKA_TOPIC_TIMESTAMP=air.timestamp.processing
KAFKA_TOPIC_PROCESSING=air.process-kafka-message
KAFKA_TOPIC_DLQ=air.event.dlq
KAFKA_CONSUMER_GROUP=air-event-processors
KAFKA_STREAMS_APP_ID=air-event-scheduler-streams

# Redis Configuration (Air-specific database)
REDIS_HOST=redis-air.example.com
REDIS_PORT=6379
REDIS_DATABASE=2
REDIS_PASSWORD=air_redis_password

# Performance Tuning for Air (Medium Volume)
BATCH_SIZE=750
EXECUTION_INTERVAL=4000
KAFKA_CONSUMER_CONCURRENCY=12
CUSTOMER_MAX_PARALLEL=15

# Logging
LOG_LEVEL=INFO
LOG_FILE=logs/air-event-scheduler.log
```

### 3. Rail Event Scheduler

Environment Variables:
```bash
# Application Identity
DEPLOYMENT_NAME=event-scheduler-rail
CUSTOMER_NAME=rail
ENVIRONMENT=production
SERVER_PORT=8082

# Database Configuration (Rail-specific DB)
DATABASE_URL=jdbc:postgresql://rail-db.example.com:5432/rail_events_db
DATABASE_USERNAME=rail_user
DATABASE_PASSWORD=rail_secure_password
DB_POOL_MAX_SIZE=35
DB_POOL_MIN_IDLE=10

# Kafka Configuration (Rail-specific topics)
KAFKA_BOOTSTRAP_SERVERS=kafka-rail.example.com:9092
KAFKA_TOPIC_SUBMISSION=rail.event.submissions
KAFKA_TOPIC_SCHEDULED=rail.event.scheduled
KAFKA_TOPIC_READY=rail.event.ready
KAFKA_TOPIC_TIMESTAMP=rail.timestamp.processing
KAFKA_TOPIC_PROCESSING=rail.process-kafka-message
KAFKA_TOPIC_DLQ=rail.event.dlq
KAFKA_CONSUMER_GROUP=rail-event-processors
KAFKA_STREAMS_APP_ID=rail-event-scheduler-streams

# Redis Configuration (Rail-specific database)
REDIS_HOST=redis-rail.example.com
REDIS_PORT=6379
REDIS_DATABASE=3
REDIS_PASSWORD=rail_redis_password

# Performance Tuning for Rail (Medium Volume)
BATCH_SIZE=600
EXECUTION_INTERVAL=5000
KAFKA_CONSUMER_CONCURRENCY=10
CUSTOMER_MAX_PARALLEL=12

# Logging
LOG_LEVEL=INFO
LOG_FILE=logs/rail-event-scheduler.log
```

### 4. Core Trac Event Scheduler

Environment Variables:
```bash
# Application Identity
DEPLOYMENT_NAME=event-scheduler-coretrac
CUSTOMER_NAME=coretrac
ENVIRONMENT=production
SERVER_PORT=8083

# Database Configuration (Core Trac-specific DB)
DATABASE_URL=jdbc:postgresql://coretrac-db.example.com:5432/coretrac_events_db
DATABASE_USERNAME=coretrac_user
DATABASE_PASSWORD=coretrac_secure_password
DB_POOL_MAX_SIZE=60
DB_POOL_MIN_IDLE=20

# Kafka Configuration (Core Trac-specific topics)
KAFKA_BOOTSTRAP_SERVERS=kafka-coretrac.example.com:9092
KAFKA_TOPIC_SUBMISSION=coretrac.event.submissions
KAFKA_TOPIC_SCHEDULED=coretrac.event.scheduled
KAFKA_TOPIC_READY=coretrac.event.ready
KAFKA_TOPIC_TIMESTAMP=coretrac.timestamp.processing
KAFKA_TOPIC_PROCESSING=coretrac.process-kafka-message
KAFKA_TOPIC_DLQ=coretrac.event.dlq
KAFKA_CONSUMER_GROUP=coretrac-event-processors
KAFKA_STREAMS_APP_ID=coretrac-event-scheduler-streams

# Redis Configuration (Core Trac-specific database)
REDIS_HOST=redis-coretrac.example.com
REDIS_PORT=6379
REDIS_DATABASE=4
REDIS_PASSWORD=coretrac_redis_password

# Performance Tuning for Core Trac (Highest Volume)
BATCH_SIZE=1500
EXECUTION_INTERVAL=2000
KAFKA_CONSUMER_CONCURRENCY=20
CUSTOMER_MAX_PARALLEL=25

# Logging
LOG_LEVEL=INFO
LOG_FILE=logs/coretrac-event-scheduler.log
```

## Docker Deployment Examples

### Ocean Deployment (docker-compose.yml)
```yaml
version: '3.8'
services:
  event-scheduler-ocean:
    image: event-scheduler:latest
    container_name: ocean-event-scheduler
    ports:
      - "8080:8080"
    environment:
      - DEPLOYMENT_NAME=event-scheduler-ocean
      - CUSTOMER_NAME=ocean
      - ENVIRONMENT=production
      - DATABASE_URL=jdbc:postgresql://ocean-db:5432/ocean_events_db
      - DATABASE_USERNAME=ocean_user
      - DATABASE_PASSWORD=ocean_secure_password
      - KAFKA_BOOTSTRAP_SERVERS=kafka-ocean:9092
      - KAFKA_TOPIC_PROCESSING=ocean.process-kafka-message
      - REDIS_HOST=redis-ocean
      - REDIS_DATABASE=1
      - BATCH_SIZE=1000
      - KAFKA_CONSUMER_CONCURRENCY=15
    depends_on:
      - ocean-db
      - kafka-ocean
      - redis-ocean
    volumes:
      - ./logs:/app/logs
    restart: unless-stopped
```

### Kubernetes Deployment Example (Ocean)
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: event-scheduler-ocean
  labels:
    app: event-scheduler
    customer: ocean
spec:
  replicas: 3
  selector:
    matchLabels:
      app: event-scheduler
      customer: ocean
  template:
    metadata:
      labels:
        app: event-scheduler
        customer: ocean
    spec:
      containers:
      - name: event-scheduler
        image: event-scheduler:latest
        ports:
        - containerPort: 8080
        env:
        - name: DEPLOYMENT_NAME
          value: "event-scheduler-ocean"
        - name: CUSTOMER_NAME
          value: "ocean"
        - name: ENVIRONMENT
          value: "production"
        - name: DATABASE_URL
          valueFrom:
            secretKeyRef:
              name: ocean-db-secret
              key: url
        - name: DATABASE_USERNAME
          valueFrom:
            secretKeyRef:
              name: ocean-db-secret
              key: username
        - name: DATABASE_PASSWORD
          valueFrom:
            secretKeyRef:
              name: ocean-db-secret
              key: password
        - name: KAFKA_BOOTSTRAP_SERVERS
          value: "kafka-ocean:9092"
        - name: KAFKA_TOPIC_PROCESSING
          value: "ocean.process-kafka-message"
        - name: REDIS_HOST
          value: "redis-ocean"
        - name: REDIS_DATABASE
          value: "1"
        - name: BATCH_SIZE
          value: "1000"
        - name: KAFKA_CONSUMER_CONCURRENCY
          value: "15"
        resources:
          requests:
            memory: "1Gi"
            cpu: "500m"
          limits:
            memory: "2Gi"
            cpu: "1000m"
        livenessProbe:
          httpGet:
            path: /actuator/health
            port: 8080
          initialDelaySeconds: 60
          periodSeconds: 30
        readinessProbe:
          httpGet:
            path: /actuator/health/readiness
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 10
```

## Benefits of Shared Binary Approach

### 1. **Application Isolation**
- Each customer has completely separate infrastructure
- No noisy neighbor problems
- Independent scaling based on customer needs
- Separate monitoring and alerting

### 2. **Operational Efficiency** 
- Single JAR file to maintain and update
- Consistent deployment process across all customers
- Easier testing and validation
- Unified monitoring and logging format

### 3. **Per-Customer Workflow Execution**
- Prevents contention between customers
- Parallel execution across deployments
- Customer-specific performance tuning
- Independent failure handling

### 4. **Workflow Efficiency**
- Batch processing completes before next cycle
- Customer-specific batch sizes and intervals
- Optimized resource allocation per customer
- Proper workflow lifecycle management

## Monitoring and Observability

Each deployment includes customer-specific tags in metrics:
- `deployment`: event-scheduler-ocean, event-scheduler-air, etc.
- `customer`: ocean, air, rail, coretrac
- `environment`: production, staging, development

This enables:
- Customer-specific dashboards
- Per-deployment alerting
- Resource utilization tracking
- Performance comparison across customers

## Scaling Guidelines

### High Volume Customers (Ocean, Core Trac)
- Larger batch sizes (1000-1500)
- Higher Kafka concurrency (15-20)
- More database connections (50-60)
- Shorter execution intervals (2-3 seconds)

### Medium Volume Customers (Air, Rail)  
- Moderate batch sizes (600-750)
- Standard Kafka concurrency (10-12)
- Standard database connections (35-40)
- Standard execution intervals (4-5 seconds)

### Resource Allocation
- CPU: 500m-1000m per instance
- Memory: 1Gi-2Gi per instance
- Replicas: 2-3 per customer for high availability

This architecture ensures that each customer gets dedicated resources while maintaining operational efficiency through a shared binary approach.
