# Using Reddis from Spring Boot (an experiment)

Because Reddis speaks RESP2 and answers the connection handshake (`HELLO 3` is refused with `NOPROTO`, so Lettuce
falls back to RESP2), a Spring Boot app can use it as a cache.

```xml
<dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-redis</artifactId></dependency>
<dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-cache</artifactId></dependency>
```
```yaml
spring:
  data.redis: { host: localhost, port: 6379 }
  cache.type: redis
```
```java
@EnableCaching @SpringBootApplication class App { }

@Service
class ProductService {
    @Cacheable("products")
    public Product find(long id) { /* slow lookup */ }
}
```
`@Cacheable` writes with `SET key value PX ttl`, and clearing a cache uses `KEYS` + `DEL`, all of which Reddis supports.

Honest note: I could not run this myself. If the client sends a command Reddis does not implement, the reply is
`ERR unknown command 'X'` and the server log shows it: add that command (a good small exercise) or note it as a limitation.