# native-image-maven-plugin
Alternative NativeImageMojo using docker-images as executables

This plugin works like 

```xml
    <groupId>org.graalvm.nativeimage</groupId>
    <artifactId>native-image-maven-plugin</artifactId>
    <version>21.2.0</version>
```

But it allows one to use a docker image as native-image executable instead of a local installation.

This may be useful to circunvent some [issues](https://github.com/oracle/graal/issues/5814) that can't be helped, except by changing the OS invoking the executable.

To use this plugin with the docker option, it is necessary to have a container image with native-image as entrypoint. E.g.

```
FROM ubuntu:20.04

RUN apt update \
    && apt install -y wget gcc zlib1g-dev \
    && wget https://github.com/graalvm/graalvm-ce-builds/releases/download/jdk-21.0.2/graalvm-community-jdk-21.0.2_linux-x64_bin.tar.gz \
    && tar -xzf graalvm-community-jdk-21.0.2_linux-x64_bin.tar.gz \
    && rm -f graalvm-community-jdk-21.0.2_linux-x64_bin.tar.gz \
    && mv graalvm-community-openjdk-21.0.2+13.1/ opt/java

ENV PATH="${PATH}:/opt/java/bin"
    
ENTRYPOINT ["native-image"]
```

Then just add the image tag in pom.xml

```
<plugin>
    <groupId>com.nc.plugins</groupId>
    <artifactId>native-image-maven-plugin</artifactId>
    <version>1.0.0</version>
    <configuration>
        <dockerImage>my.company/native-image-builder:latest</dockerImage>
        <buildArgs>
            <buildArg>...</buildArg>
            <buildArg>--static</buildArg>
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

```
docker container run --workdir $PWD/target --user $(id -u):$(id -g) -v $HOME:$HOME --rm my.company/native-image-builder:latest -cp <project classpath computed by maven> -H:Class=my.native.image.Entrypoint 
```

## Plugin automatic configurations & options

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

In order to not run as root, an thus prevent further cleaning of the output by the current user, the plugin will determine the current user id and gid, and force a **--user**.

The id and gid are determined by calling 

```java
int uid = (Integer) Files.getAttribute(home, "unix:uid");
int gid = (Integer) Files.getAttribute(home, "unix:gid");
```

If either of these calls fail, the plugin will not run with --user. If running as root is not acceptable, then

```
    <configuration>
        <enforceUID>true</enforceUID>
        ...
    </configuration>
```

should be configured.

### Alternative Entrypoint

If one wishes to use a docker image which packs graal but does not use native-image as entrypoint, then this can be configured as

```
    <configuration>
        <dockerEntryPoint>/my-graalvm/bin/native-image</dockerEntryPoint>
        ...
    </configuration>
```

Which will translate into 

```
docker container run --entrypoint /my-graalvm/bin/native-image ...
```


### Output

The plugin will set **workdir** based on project.build.directory, so the output of the executable can be placed on <project>/target, as if running the plugin with a local native-image executable.
