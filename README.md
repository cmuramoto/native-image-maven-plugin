# native-image-maven-plugin
Alternative NativeImageMojo using docker-images as executables

This plugin works like 

```xml
    <groupId>org.graalvm.nativeimage</groupId>
    <artifactId>native-image-maven-plugin</artifactId>
    <version>21.2.0</version>
```

But it allows one to use a docker image as native-image executable instead of a local installation.

This approach may be useful to circumvent some [issues](https://github.com/oracle/graal/issues/5814) that can't be helped, except by changing the OS invoking the executable, or messing with alternative glibc versions.

To use this plugin with the **dockerImage** option, it is necessary to have a container image with native-image as entrypoint. E.g.

```
FROM ubuntu:20.04

RUN apt update \
    && apt install -y wget gcc zlib1g-dev \
    mkdir -p /opt/java \
    && wget https://github.com/graalvm/graalvm-ce-builds/releases/download/jdk-22.0.0/graalvm-community-jdk-22.0.0_linux-x64_bin.tar.gz \
    && tar -xzf graalvm-community-jdk-22.0.0_linux-x64_bin.tar.gz -C /opt/java --strip-components=1 \
    && rm -f *.tar.gz \

ENV PATH="${PATH}:/opt/java/bin"
    
ENTRYPOINT ["native-image"]
```

With such an image ready, the plugin can be used by just adding the **dockerImage** tag under the *configuration* of the plugin in your pom.xml

```
<plugin>
    <groupId>com.nc.plugins</groupId>
    <artifactId>native-image-maven-plugin</artifactId>
    <version>1.0.0</version>
    <configuration>
        <dockerImage>my.company/native-image-builder:latest</dockerImage>
        <buildArgs>
            <buildArg>...</buildArg>
        </buildArgs>
        <mainClass>${native.image.entrypoint}</mainClass>
    </configuration>
    <executions>
        <execution>
            <goals>
                <goal>native-image</goal>
            </goals>
            <phase>package</phase>
        </execution>
    </executions>
</plugin>
```

The plugin will issue a command as if writing the following statements on a shell:

```bash
docker container run --workdir $PWD/target --user $(id -u):$(id -g) -v $HOME:$HOME --rm my.company/native-image-builder:latest -cp <project classpath computed by maven> -H:Class=my.native.image.Entrypoint 
```

## Static Binaries wrapped in scratch images & DNS

If you want to use [*scratch*](https://hub.docker.com/_/scratch) to build the smallest possible image, you'll need a fully [statically linked native image](https://www.graalvm.org/22.0/reference-manual/native-image/StaticImages/).

The previous docker config already includes the necessary dependencies and is just matter of adding

```
...
            <buildArg>--static</buildArg>
...
```

Static images wrapped in scratch are great, but there are some caveats.

The most common issue is the lack of DNS support and depending on the conditions, a DNS lookup may crash the program, which is quite frequent when a Virtual Thread invokes java.net.InetAddress.PlatformResolver::lookupByName.

This function will end up in JNI land when an address is first queried (e.g. by java.net.InetAddress::getAllByName), and the corresponding native function **getaddrinfo**, does not work.

While there are workarounds, e.g., by implementing a custom [InetAddressResolverProvider](https://download.java.net/java/early_access/jdk23/docs/api/java.base/java/net/spi/InetAddressResolverProvider.html)
which could use, let's say, another microservice to respond to DNS requests it might be too cumbersome.

An out-of-the-box solution consists of replacing **libc** with **musl**. From a config perspective, it is just another arg: 

```
...
            <buildArg>--libc=musl</buildArg>
...
```

However, to effectively use, we need a base image with the musl & companions installed:

```
FROM ubuntu:20.04 as base

RUN apt update && apt install -y gcc build-essential wget
    
RUN wget https://zlib.net/zlib-1.3.1.tar.gz \
    && tar -xzf zlib-1.3.1.tar.gz \
    && rm -f *.tar.gz
    
RUN wget http://more.musl.cc/10/x86_64-linux-musl/x86_64-linux-musl-native.tgz \
    && tar -xzf x86_64-linux-musl-native.tgz \
    && rm -f *.tgz

RUN mkdir -p /opt/java \
    && wget https://github.com/graalvm/graalvm-ce-builds/releases/download/jdk-22.0.0/graalvm-community-jdk-22.0.0_linux-x64_bin.tar.gz \
    && tar -xzf graalvm-community-jdk-22.0.0_linux-x64_bin.tar.gz -C /opt/java --strip-components=1 \
    && rm -f *.tar.gz \
    && rm -f /opt/java/lib/src.zip \
    && rm -f /opt/java/lib/svm/builder/svm.src.zip
    
RUN cd zlib-1.3.1 \
    && CC=/x86_64-linux-musl-native/bin/gcc \
    && ./configure --prefix=/x86_64-linux-musl-native --static \
    && make && make install \
    && rm -rf zlib-1.3.1

FROM ubuntu:20.04

COPY --from=base /x86_64-linux-musl-native /x86_64-linux-musl-native
COPY --from=base /opt/java /opt/java

RUN apt update && apt install -y gcc zlib1g-dev

ENV PATH="${PATH}:/opt/java/bin:/x86_64-linux-musl-native/bin"
    
ENTRYPOINT ["native-image"]
```

## Plugin defaults & options

### Volumes

By default, the plugin will mount

1. user.home
2. project.build.directory (target), *if* the repository is not prefixed by user.home 
3. maven.home ( .m2/repository), *if* the repository is not prefixed by user.home

In most development scenarios only user.home will be mounted, since both .m2 and sources are children directories of $HOME.

This defaults can be disabled by specifiying:

```
    <configuration>
        <disableAutomaticVolumes>true</disableAutomaticVolumes>
        ...
    </configuration>
```

Additional volumes mappings can be specified by:

```
    <configuration>
        <volumes>
            <property>
                <name>/my/local/volume</name>
                <value>/my/container/volume</value>									
            </property>
            <property>
                <name>/another</name>
                <value>/another/container/volume</value>									
            </property>
        </volumes>
        ...
    </configuration>
```

If **disableAutomaticVolumes** is not set to true, the volumes will only be mounted if they are not prefixed by $HOME.

### Permissions

In order to not run as root, and thus prevent further cleaning of the output by the current user, the plugin will determine the current user id and gid, and force a **--user**.

The id and gid are determined by calling 

```java
int uid = (Integer) Files.getAttribute(home, "unix:uid");
int gid = (Integer) Files.getAttribute(home, "unix:gid");
```

If either of these calls fails, the plugin will not run with --user, falling back to *root* (or the default user of the image). If running as root is not acceptable, then

```
    <configuration>
        <enforceUID>true</enforceUID>
        ...
    </configuration>
```

should be configured.

### Alternative Entrypoint

If one wishes to use a docker image that packs graalvm but does not use native-image as entrypoint, then this can be configured as

```
    <configuration>
        <dockerImage>my.company/image-with-graal-jdk:latest</dockerImage>
        <dockerEntryPoint>/my-graalvm/bin/native-image</dockerEntryPoint>
        ...
    </configuration>
```

Which will translate into 

```
docker container run --entrypoint /my-graalvm/bin/native-image ...
```


### Output

The plugin will set **workdir** based on project.build.directory, so the executable output can be placed on <project>/target, as if running the plugin with a local native-image executable.
