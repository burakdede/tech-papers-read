# Amazon Dynamo


## Motivation 

- Amazon wants to keep their server up and running in the midst of network failures, natural disasters even tornados destroying data centers.
- They have very strict uptime requirements %99.99 and each downtime cost them a lot of money and customer unsatisfaction.
- Build a system even during failures that we can write and read from by **sacrificing consistency**.
- It is possible to build a reliable, high performance and eventually consistent storage system and use it on production with demanding apps.


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
- with network partitioning you can't get both strong consistency and high availability.
- those systems make data unavailable until they are sure that it is consistent across cluster. (not gonna work for Amazon)
- during problems they may even reject writes to cluster (not gonna work for Amazon)
- Amazon choose to design it in a way that system will work with optimistic replication & async. in the background.
- This may lead to conflicting changes and two question arise
	- where to resolve conflicts? (at data store or application)
	- when to resolve conflicts? (during writes or reads)
- Most systems use writes to resolve conflicts and do it at data store
- This has the problem of rejecting writes if majority of cluster not available (not gonna work for Amazon)
- Instead Dynamo pushes conflict resolution to reads and client so that client can decide what to do with versions (eg. merge)

### Related Work

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
	- put(key, context, object)
- key and object treatead as array of bytes
- get(key) will take the key - hash it to find the node storing it in the ring of nodes and return single or list of conflicting items.
- post(key, context, object) will take the key - hash it and find the place in ring of nodes - replicate it to N preference list and save to each node.
- all ring walk happens in clockwise
- it uses [Consisten Hashing](https://en.wikipedia.org/wiki/Consistent_hashing) to scale to hosts.
- basically we are mapping key value via hash onto a ring of values.
- advantage: if node leaves the ring only adjacent nodes effected not the rest of the system (they shared the load between each other)
- considering N values in the ring with K hosts it will only take O(N/K) to remove or add host.
- This is a good [visual explanation](https://www.youtube.com/watch?v=--4UgUPCuFM)

> even simple consistent hashing load will not distrubte evently. Consider heterogeneity of machine, machine with more resources should not get the same load as machine with less. 

- divide the ring into sections more than number of hosts (K being hosts N being sections N > K)
- assign each host to multiple sections so each host will manage sections according to their machine capacity
- not every point on the ring will be a physical host machine, they call it virtual nodes.

> if we remove a node from the ring, the load or sections managed by this node will evenly distributed among other nodes. same applies if we add some other node it will get almost equal amount of load from other nodes. 


### Replication

- to achieve high availability and durability dynamo replicates the data to multiple hosts
- when request reaches to destination node its that node responsibility to replicate it to others hence it is called `coordinator node`.
- it will walk over the ring clockwise to replicate the data to N nodes which is called `preference list`
- after data replicated to all nodes in preference list result will return to client.
- since it will start walking N nodes those N nodes may not be exactly physical nodes (we divide the ring to virtual and physical nodes) so algorith skip the virtual nodes during the session.

### Data Versioning

- dynamodb is eventually consistent and eventually part is coming from how replication works internally.
- put() replication happen asynchronously and before all replicas have the data client may already get the response. so subsequent get() calls may return out of date data.
- amazon services works under these conditions /  it is ok for shopping cart service that old items to resurface as long as writes are still there & operational. (at most they sell extra item and say sorry & refund)
- dynamo uses Vector Clocks for this purpose, it is basically node -> counter mapping and allow to understand causality.
- So each node will increment their counter (or timestamp)
- As a result of get() we may get list of vector clocks for historical events.
- if all counters on one vector clock is less than or equal the other first one is ancestor of second and can be ignored
- if not they are parallel branches and need reconciliation.
- [why vector clocks are hard](https://riak.com/posts/technical/why-vector-clocks-are-hard/index.html)
- [why vector clocks are easy](https://riak.com/posts/technical/why-vector-clocks-are-easy/index.html)


#### Vector Clocks

- helps us to tell causality relation in the system with multiple processes
- consider there are N processes each process
	- when sending a message or executing step increments its local vector clock for that process
	- when receiving a message receiver process also increment its local vector clock and also merge max of the all other processes vector clocks.
	- we don't know the order or turn of messages appear but each will cary its vector clock payload with itself. more space but allow us to tell which happened before or later.
	- [nice visual explanation](https://www.youtube.com/watch?v=jD4ECsieFbE)

- these can grow to certain extent but considering requests will be handled by N pref. list nodes it will be capped by that number (**except during the network partitions.**)


### Exectuion of get() and put() opeartions

- two options AWS generic HTTP load balancer or partition aware client
- second option causes less latency as it results in less network hop
- put operation
	- client directly redirect request to coordinator node
	- coordinator node asks W number of node out of N-1 to confirm write (W: being min number of write ACK from nodes)
	- depeding on how many reachable from the top N-1 list return result of all data versions to client
- get operation
	- client directly redirect request to coordinator node
	- coordinator node asks R number of node out of N-1 to confirm read (R: being min number of read ACK from nodes)
	- depeding on how many reachable from the top N-1 return all versions to client (with vector clocks for receonciliation)
- quorum like system, N total number of preference list, R total number of read ACKs and W total number of write ACKs
- if we set R + W > N it basically forms quorum system meaning that your are gonna read your writes (since there will be overlap on your reads vs writes)

### Handling Failure : Hinted Handoff

- during network partition if we keep the dynamo design as it is it would lead to data loss especially on writes.
- instead when coordinator nodes looks for N-1 pref. list node it may not be able to reach that number because of network partitioning (eg. nodes being down in certain data center)
- instead of going to actual N-1 host it may put the data to another host which is not in the preferences list. It also leaves metadata stating the original owner host of the data. This process called **hinted handoff**.
- host that have the hinted data will store it in a different database and scan failed host periodically to replicate the data back to original owner.
- Also most of the replication happens between different regions & data centers to achive more durability (as data centers can go down in different regions)

### Handling Permanent Failures : Replica Sync.

- sometimes even backup hosts can have a permanent problem so dynamo need to quickly tell if two host are out of sync.
- for that dynamo uses **Merkle Tree** data structure. Basically every node keeps a merkle tree for all the set of key in their range.
- keys start forming hashing from bottom to top. leaves are the individual hashes of the keys, parents are hashes of the hashes all the way to the root.
- dynamo start checking root and work its way down to leaves if it find discrepancies between two hosts.
- merkle tree make this operation faster and also data transfer for synchronization becomes smaller.
- more info on [Merkle Trees](https://en.wikipedia.org/wiki/Merkle_tree)


### Membership & Failure Detection

- gossip based protocol means every second pick random host from the ring and exchange information about memberships
- while doing that exchange also give information about which host owns which virtual nodes so that other nodes can redirect to correct coordinator node for read/write operations.
- now when A joins to ring and B joins to ring they don't know each other so this may create logical partitions.
- to prevent that when joining nodes get a static configuration via manually or some configuration service.
- seeds are the name for these and they are fully functional nodes in the ring.

- when node A tries to re-route request to node B and B is unresponsive to A (while responsive to C) A will periodically check B but eventually will mark it as unhealthy and will try other alternatives.
- there is no need for global view of the failure state
	- first, every system will get the node addition and removal via seed nodes and central methods
	- second, communication failures between nodes will be propogated to system eventually.

### Adding & Removing Nodes

- when adding a node that node will receive number of tokens randomly selected from ring.
- during this period other nodes that is once responsible for respective keys start migrating their keys to newly added node with confirmation.
- when removal happens reallocation of the keys happens by old nodes.


## Implementation

- each node has the following responsibilities, request routing, membership, failure detection and persistance
- for persistance dynamodb has pluggable enginer you can use berkley db or mysql depending on your data patterns. (berkley db is good for small object sizes while mysql has advantage for big ones) (all implemented in java)
- each node coordinating the request keep a state machine.
- for read starting with coordinator node
	- re-route request to N-1 nodes for replication
	- wait for min required number of responses
	- if it can't reach to min within time bound fail req.
	- if it reaches pack all versions & do reconciliation & respond.

- almost same process for write but any top N nodes can be coordinator and will be chosen based on their latest read response times (fastest wins as it will help to quickly respond to following read operation - read your write)

## Experiences & Lessons Learned

- dynamodb has several configurations for reconciliation
	- business logic based : client decides how to reconcile multiple versions like shopping cart merging them
	- time based : this is basically last write wins type, session service works with this
	- high perf. read : you basically set R = 1 and all reads will be really quick and you set your W = N where your writes takes time and durable. product catalog and promotion lists are based on this config
- you can decide which configuration by changing client settings.
- quorum states that for consistent R and W, N >= R + W so for 5 node cluster W = 4 and R = 3 will result in overlap and you can at least guarantee you will read your writes.
- amazon common configurat for N, W, R is (3, 2, 2)
- each host connected by high speed networks and of course effected by latencies.



	
	
	
	
	
	
	
	
	
	
	
	