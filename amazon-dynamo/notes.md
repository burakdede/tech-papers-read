# Amazon Dynamo


## Motivation 

- Amazon wants to keep their server up and running in the midst of network failures, natural disasters even tornados.
- They have very strict uptime requirements %99.99 and each downtime cost them a lot of money and customer unsatisfaction.
- Built a system even during failures that we can write and read from by **sacrificing consistency**.


## Initial Decisions
- key:value store. why? well most of their data access does not require joins or relational queries, mostly primary key based data access (eg. shopping cart, top seller list, user preferences...)
- lets sacrifice consistency for availability, we are fine with item popping up or disappearing all of a sudden (fix it with good customer support I guesss)


## Background
- stateless service: that aggregates results from other services
- statefull service: results comes from exeucting business logic which recent state stored in persistent store.
- traditionally most services use relational data stores
	- only PK based access makes it unnecassary
	- they choose consistency over availability
	- they are not easy to scale and requires highly skilled people
	- still not highly scalable replication techniques to fit to their needs

### System Assumptions & Requirements
- Query Model: Simple read & write by key value, no operations spanning multiple items and no join like requirements
- ACID Properties: (atomicity, consistency, isolation, durability). In order to provide high availability dynamo have weaker consistency property. ACID property providing data stores generally are not very good at availability portion. No isolation guarentees with certain consistency window.
- 99.9 percentile defines the SLAs so it should easily run on commodity hardware and scale out.
- No autohrization, authentication since it will run in non-hostile env.

### SLAs
- hundred services calling/depeding other tens of services, sort of like a call graph. Some are aggregators with aggressive caching other statefull.
- client - server agreement
- client provides peak request distribution and server need to respond within bounded time
- eg. client with 500req/s peak request will return under 300ms for %99.9
- normally calculated with median, averages and expected variance
- Amazon chooses %99.9 to cover customers with long history with platform

### Design Considerations
- most well known systems (RDMS) especially with ACID properties favor consistency over availability.
- with network partitioning you can get both strong consistency and high availability.
- those systems make data unavailable until they are sure that it is consistent across cluster. (not gonna work for Amazon)
- during problems they may even reject writes to cluster (not gonna work for Amazon)
- Amazon choose to design it in a way that system will work with optimistic replication & async. in the background.
- This may lead to conflicting changes and two question arise
	- where to resolve conflicts? (at data store or application)
	- when to resolve conflicts? (during writes or reads)
- Most systems use writes to resolve conflicts and do it at data store
- This has the problem of rejecting writes if majority of cluster not available (not gonna work for Amazon)
- Instead Dynamo pushes conflict resolution to reads and client so that client can decide what to do with versions (eg. merge)

### Related Work
skipped this part 

## System Architecture
- storage system like this need to deal with lots of things
	- persisting
	- scaling & partitioning
	- membership detection
	- failure detection & recovery
	- replication
	- request routing / load balancing
	- requst marshaling & routing
	- system monitoring & configuration management
- paper does not touch all the topics though
- partitioning: consistent hashing - incremental scalability
- high availability for writes - vector clocks with read time conflict resolution - writes are always on
- handling temporary failures - hinted hand off - give me your data for now I will it back to you later when you feel OK. 
- recovering from permanent failures - merkle trees - fast way to check how off your data from other nodes
- membership detection - gossip based protocol - no central membership registry

### System Interface
- small set of operations
	- get(key)
	- post(key, context, object)
- key and object treatead as array of bytes
- get(key) will take the key - hash it to find the node storing it in the ring of nodes and return single or list of conflicting items.
- post(key, context, object) will take the key - hash it and find the place in ring of nodes - replicate it to N preference list and save to each node.
- all ring walk happens in clockwise
- it uses [Consisten Hashing](https://en.wikipedia.org/wiki/Consistent_hashing) to scale to hosts.
- basically we are mapping key value via hash onto a ring of values.
- advantage: if node leaves the ring only adjacent nodes effected not the rest of the system (they shared the load between each other)
- when new node added also it will only effect the adjacent nodes in the ring by taking part of the their load.
- considering N values in the ring with K hosts it will only take O(N/K) to remove or add host.
- This is a good [visual explanation](https://www.youtube.com/watch?v=--4UgUPCuFM)

> even simple consistent hashing load will not distrubte evently. Consider heterogeneity of machine, machine with more resources should not get the same load as machine with less. 

- divide the ring into sections more than number of hosts (K being hosts N being sections N > K)
- assign each host to multiple sections so each host will manage sections according to their machine capacity
- not every point on the ring will be a physical host machine, they call it virtual nodes.

> if we remove a node from the ring, the load or sections managed by this node will evenly distributed among other nodes. same applies if we add some other node it will get almost equal amount of load from other nodes. 


### Replication
- to achieve high availability and durability dynamo replicates the data to multiple hosts
- when request reaches to destination node its that node responsibility to replicate it to others hence it is called `coordinator node`.
- it will walk over the ring clockwise to replicate the data to N nodes which is called `preference list`
- after data replicated to all nodes in preference list result will return to client.
- since it will start walking N nodes those N nodes may not be exactly physical nodes (we divide the ring to virtual and physical nodes) so algorith skip the virtual nodes during the session.

### Data Versioning
- dynamodb is eventually consistent and eventually part is coming from how replication works internally.
- put() replication happen asynchronously and before all replicas have the data client may already get the response. so subsequent get() calls may return out of date data.
- amazon services works under these conditions /  it is ok for shopping cart surface that old items to resurface as long as writes are still there & operational.
- dynamo uses Vector Clocks for this purpose, it is basically node -> counter mapping and allow to understand causality. (which caused which one or which one is a result of which kind of questions)
- So each node will increment their counter (or timestamp)
- As a result of get() we may get list of vector clocks for historical events.
- if all counters on one vector clock is less than or equal the other first one is ancestor of second and can be ignored
- if not they are parallel branches and need reconciliation.
- [why vector clocks are hard](https://riak.com/posts/technical/why-vector-clocks-are-hard/index.html)
- [why vector clocks are easy](https://riak.com/posts/technical/why-vector-clocks-are-easy/index.html)






























