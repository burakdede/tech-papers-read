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










