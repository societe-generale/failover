# REDESIGN OBSERVABILITY & DASHBOARD

## Overview

Currently, our observability and dashboard features are limited in functionality and user experience. This redesign aims to enhance the usability, performance, and visual appeal of these features to provide a more comprehensive and intuitive experience for our users.

## Current Status
- Currently, we have a dedicated module for Observability [failover-observable-micrometer](failover-observable-micrometer)
- The observable metrics are published from core module [failover-core](failover-core) and are consumed by the micrometer module to be published to the monitoring system (e.g., Prometheus).
- Current metrics are limited ( ex: only Counters, Timer are used so far, make use of guard, histogram etc for more useful metrics cross failover applications )

    [ failover-core] publish (abstracted publisher) -> [failover-observable-micrometer] Micrometer Publisher  -> [failover-dashboard] internal dashboard [html,css,js]  ( with actuator endpoints for metrics and health checks )

- with actuator endpoints, we have some basic metrics and health checks, but they are not comprehensive and lack a user-friendly interface for monitoring and troubleshooting.

## Challenges 
- The current model works very well with single jvm applications, but it becomes less effective in distributed environments where multiple instances of the application are running.


## Proposed Solutions
- Redesign the observability and dashboard features to provide a more comprehensive and intuitive experience for users.
- add more modules for supporting distributed monitoring as an extensions of failover-observable-micrometer ( ex:  Prometheus , ElasticSearch etc ) 
- add mode modules for extension of observable to ELK stack ( ex: logstash, filebeat etc )

- IT SHOULD WORK WITH SINGLE JVM APPLICATIONS AS WELL AS DISTRIBUTED ENVIRONMENTS, PROVIDING A SEAMLESS EXPERIENCE FOR USERS REGARDLESS OF THEIR DEPLOYMENT ARCHITECTURE.


The idea is : each instance can have a configurations (in yml) to publish the mercies to a centralized monitoring system (/endpoints)

The dashboard can also have a configuration (in yml) to pull and aggregate the metrics from the centralized monitoring system and display them in a user-friendly interface.

PLEASE PROVIDE YOUR FEEDBACKS AND SUGGESTIONS TO IMPROVE THE PROPOSED SOLUTIONS.

AND FINALLY, PLEASE SHARE YOUR THOUGHTS ON THE PROPOSED SOLUTIONS AND ANY ADDITIONAL FEATURES YOU WOULD LIKE TO SEE IN THE REDESIGNED OBSERVABILITY AND DASHBOARD.

WRITE ALL THESE WITH A DETAILED DESIGN ( include diagrams if possible ) AND IMPLEMENTATION PLAN ( include timelines and milestones and phase by phase implementation plan  ) IN THE PROPOSED SOLUTIONS SECTION.

DESIGN MUST BE : 
- scalable
- extendable
- flexible
- user-friendly
- performant
- modular 