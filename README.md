# osgi-target-maven-plugin

This maven plugin is used to generate an Eclipse .target file. This allows for easy debugging of OSGi bundles within the Eclipse environment leveraging the Eclipse PDE.

Other tools such as Tycho have similar capabilities but bring a much heavier environment that must be set up.

To use, simply define all your target dependencies within a project that then uses the osgi-target-maven-plugin. NOTE: The plugin leverages functionality from the Maven dependency plugin so that needs to be added as a plugin dependency.

If you had a simple POM such as:

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>myproject</groupId>
    <artifactId>myproject.target</artifactId>
    <version>1.0-SNAPSHOT</version>
    <name>MyProject Target</name>
    <dependencies>
        <dependency>
            <groupId>org.eclipse.platform</groupId>
            <artifactId>org.eclipse.equinox.ds</artifactId>
            <version>1.5.0</version>
        </dependency>
    </dependencies>
    <build>
        <plugins>
            <plugin>
                <groupId>com.diamondq.maven</groupId>
                <artifactId>osgi-target-maven-plugin</artifactId>
                <version>1.0.1</version>
                <executions>
                    <execution>
                        <id>build-target</id>
                        <phase>process-resources</phase>
                        <goals>
                            <goal>build-target</goal>
                        </goals>
                        <configuration>
                            <targetName>MyProject Target</targetName>
                            <outputFile>${project.basedir}/myproject.target</outputFile>
                            <excludeArtifactIds>osgi.core,osgi.cmpn</excludeArtifactIds>
                        </configuration>
                    </execution>
                </executions>
                <dependencies>
                    <dependency>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-dependency-plugin</artifactId>
                        <version>3.1.1</version>
                        <type>maven-plugin</type>
                    </dependency>
                </dependencies>
            </plugin>
            <!-- Since the target contains absolute paths, we don't want 
                to deploy it to a permanent/remote repository -->
            <!-- However, since it's only used for the build process, as 
                long as it gets deployed to the local repository, we're fine. -->
            <plugin>
                <artifactId>maven-deploy-plugin</artifactId>
                <version>2.8.2</version>
                <executions>
                    <execution>
                        <id>default-deploy</id>
                        <configuration>
                            <skip>true</skip>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

you would end up with a .target file that looks like:

```xml
<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<target name="MyProject Target" sequenceNumber="1">
<locations>
	<location path="C:\Users\mmansell\.m2\repository\org\eclipse\platform\org.eclipse.equinox.ds\1.5.0" type="Directory"/>
	<location path="C:\Users\mmansell\.m2\repository\org\apache\felix\org.apache.felix.scr\2.1.0" type="Directory"/>
	<location path="C:\Users\mmansell\.m2\repository\org\codehaus\mojo\animal-sniffer-annotations\1.9" type="Directory"/>
</locations>
</target>
```

Opening the .target file in Eclipse (with the PDE tools installed) allows you to make it the default platform, and then use it for Debug/Run Configurations.

This plugin provides almost all the configuration properties that the `dependency:list` does with the addition of `<targetName>` to override the name within the .target file.

# Some helpful dependencies

## Basic Eclipse Equinox Oxygen setup

Yes, you can pare this down farther, but this provides a good environment (a Console, Config Admin, Declarative Services, Prefs).

```xml
        <dependency>
            <groupId>org.eclipse.platform</groupId>
            <artifactId>org.eclipse.core.contenttype</artifactId>
            <version>3.6.0</version>
        </dependency>
        <dependency>
            <groupId>org.eclipse.platform</groupId>
            <artifactId>org.eclipse.core.jobs</artifactId>
            <version>3.9.3</version>
        </dependency>
        <dependency>
            <groupId>org.eclipse.platform</groupId>
            <artifactId>org.eclipse.core.runtime</artifactId>
            <version>3.13.0</version>
        </dependency>
        <dependency>
            <groupId>org.eclipse.platform</groupId>
            <artifactId>org.eclipse.equinox.cm</artifactId>
            <version>1.2.0</version>
        </dependency>
        <dependency>
            <groupId>org.eclipse.platform</groupId>
            <artifactId>org.eclipse.equinox.common</artifactId>
            <version>3.9.0</version>
        </dependency>
        <!-- Felix Gogo is the implementation for Equinox Console. However, the
            Equinox POM allows loading anything greater or equal to 0.10,
            while the OSGi Import-Package header binds against just 0.10.
            If we don't pin the gogo libraries to 0.10, then later ones will
            be implicitly loaded by Maven and then not work due to constraints in OSGi -->
        <dependency>
            <groupId>org.apache.felix</groupId>
            <artifactId>org.apache.felix.gogo.shell</artifactId>
            <version>0.10.0</version>
        </dependency>
        <dependency>
            <groupId>org.apache.felix</groupId>
            <artifactId>org.apache.felix.gogo.command</artifactId>
            <version>0.10.0</version>
            <exclusions>
                <exclusion>
                    <groupId>org.osgi</groupId>
                    <artifactId>org.osgi.core</artifactId>
                </exclusion>
                <exclusion>
                    <groupId>org.osgi</groupId>
                    <artifactId>org.osgi.compendium</artifactId>
                </exclusion>
            </exclusions>
        </dependency>
        <dependency>
            <groupId>org.eclipse.platform</groupId>
            <artifactId>org.eclipse.equinox.console</artifactId>
            <version>1.1.300</version>
        </dependency>
        <!-- Felix SCR is the implementation for Equinox DS. However, something 
            is broken in the 2.1.x versions with the 1.5.0 versions of ds, and the ds 
            dependency will load anything from 2.0 to 3.0 so locking to 2.0.x -->
        <dependency>
            <groupId>org.apache.felix</groupId>
            <artifactId>org.apache.felix.scr</artifactId>
            <version>2.0.14</version>
        </dependency>
        <dependency>
            <groupId>org.eclipse.platform</groupId>
            <artifactId>org.eclipse.equinox.ds</artifactId>
            <version>1.5.0</version>
        </dependency>
        <dependency>
            <groupId>org.eclipse.platform</groupId>
            <artifactId>org.eclipse.equinox.event</artifactId>
            <version>1.4.0</version>
        </dependency>
        <dependency>
            <groupId>org.eclipse.platform</groupId>
            <artifactId>org.eclipse.equinox.launcher</artifactId>
            <version>1.4.0</version>
        </dependency>
        <dependency>
            <groupId>org.eclipse.platform</groupId>
            <artifactId>org.eclipse.equinox.preferences</artifactId>
            <version>3.7.0</version>
        </dependency>
        <dependency>
            <groupId>org.eclipse.platform</groupId>
            <artifactId>org.eclipse.equinox.region</artifactId>
            <version>1.4.0</version>
        </dependency>
        <dependency>
            <groupId>org.eclipse.platform</groupId>
            <artifactId>org.eclipse.equinox.registry</artifactId>
            <version>3.7.0</version>
        </dependency>
        <dependency>
            <groupId>org.eclipse.platform</groupId>
            <artifactId>org.eclipse.equinox.supplement</artifactId>
            <version>1.7.0</version>
        </dependency>
        <dependency>
            <groupId>org.eclipse.platform</groupId>
            <artifactId>org.eclipse.equinox.transforms.hook</artifactId>
            <version>1.2.0</version>
        </dependency>
        <dependency>
            <groupId>org.eclipse.platform</groupId>
            <artifactId>org.eclipse.equinox.weaving.hook</artifactId>
            <version>1.2.0</version>
        </dependency>
        <dependency>
            <groupId>org.eclipse.platform</groupId>
            <artifactId>org.eclipse.osgi</artifactId>
            <version>3.12.100</version>
        </dependency>
        <dependency>
            <groupId>org.eclipse.platform</groupId>
            <artifactId>org.eclipse.osgi.compatibility.state</artifactId>
            <version>1.1.0</version>
        </dependency>
        <dependency>
            <groupId>org.eclipse.platform</groupId>
            <artifactId>org.eclipse.osgi.services</artifactId>
            <version>3.6.0</version>
        </dependency>
        <dependency>
            <groupId>org.eclipse.platform</groupId>
            <artifactId>org.eclipse.osgi.util</artifactId>
            <version>3.4.0</version>
        </dependency>
```

## Adding the PAX Logging to allow support of SLF4J, JUL, JCL and LOG4J logging

Don't forget to add a config file for the logging (see the PAX-LOGGING docs)

```xml
        <dependency>
            <groupId>org.ops4j.pax.logging</groupId>
            <artifactId>pax-logging-api</artifactId>
            <version>1.10.1</version>
        </dependency>
        <dependency>
            <groupId>org.ops4j.pax.logging</groupId>
            <artifactId>pax-logging-service</artifactId>
            <version>1.10.1</version>
        </dependency>
```

## Add support for ServiceLoaders via Apache Aries SPIFLY

```xml
        <dependency>
            <groupId>org.apache.aries</groupId>
            <artifactId>org.apache.aries.util</artifactId>
            <version>1.1.3</version>
        </dependency>
        <dependency>
            <groupId>org.ow2.asm</groupId>
            <artifactId>asm-commons</artifactId>
            <version>5.0.4</version>
        </dependency>
        <dependency>
            <groupId>org.ow2.asm</groupId>
            <artifactId>asm</artifactId>
            <version>5.0.4</version>
        </dependency>
        <dependency>
            <groupId>org.apache.aries.spifly</groupId>
            <artifactId>org.apache.aries.spifly.dynamic.bundle</artifactId>
            <version>1.0.10</version>
            <exclusions>
                <exclusion>
                    <groupId>org.ow2.asm</groupId>
                    <artifactId>asm-debug-all</artifactId>
                </exclusion>
            </exclusions>
        </dependency>
```
