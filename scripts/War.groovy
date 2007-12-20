/*
 * Copyright 2004-2005 the original author or authors.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.codehaus.groovy.grails.compiler.support.*

/**
 * Gant script that creates a WAR file from a Grails project
 * 
 * @author Graeme Rocher
 *
 * @since 0.4
 */

Ant.property(environment:"env")                             
grailsHome = Ant.antProject.properties."env.GRAILS_HOME"    
                              

includeTargets << new File ( "${grailsHome}/scripts/Clean.groovy" ) 
includeTargets << new File ( "${grailsHome}/scripts/Package.groovy" )

task ('default':'''Creates a WAR archive for deployment onto a Java EE application server.

Examples: 
grails war
grails prod war
''') {
    depends( checkVersion)

	war()
} 

generateLog4jFile = true

target (war: "The implementation target") {
	depends( clean,  packageApp)
	 
	try {
        stagingDir = "${basedir}/staging"		

        if(config.grails.war.destFile || args) {
            warName = args ? args.trim() : config.grails.war.destFile
            String parentDir = new File(warName).parentFile.absolutePath
            stagingDir = "${parentDir}/staging"
        }		
        else {
            def fileName = grailsAppName	
            def version = Ant.antProject.properties.'app.version'
            if (version) {
                version = '-'+version
            } else {
                version = ''
            }
            warName = "${basedir}/${fileName}${version}.war"
        }
        Ant.mkdir(dir:stagingDir)

		Ant.copy(todir:stagingDir, overwrite:true) {
			fileset(dir:"${basedir}/web-app", includes:"**") 
		}       
		Ant.copy(todir:"${stagingDir}/WEB-INF/grails-app", overwrite:true) {
			fileset(dir:"${basedir}/grails-app", includes:"views/**")
		}
		Ant.copy(todir:"${stagingDir}/WEB-INF/classes") {
            fileset(dir:classesDirPath)
        }

        Ant.copy(todir:"${stagingDir}/WEB-INF/classes", failonerror:false) {
            fileset(dir:"${basedir}/grails-app/conf", includes:"**", excludes:"*.groovy, log4j*, hibernate, spring")
            fileset(dir:"${basedir}/grails-app/conf/hibernate", includes:"**/**")
            fileset(dir:"${basedir}/src/java") {
                include(name:"**/**")
                exclude(name:"**/*.java")
            }
        }
		              
		scaffoldDir = "${stagingDir}/WEB-INF/templates/scaffolding"
		packageTemplates()

		Ant.copy(todir:"${stagingDir}/WEB-INF/lib") {
			fileset(dir:"${grailsHome}/dist") {
					include(name:"grails-*.jar")
			}
			fileset(dir:"${basedir}/lib") {
					include(name:"*.jar")
			}
			if(config.grails.war.dependencies instanceof Closure) {
				def fileset = config.grails.war.dependencies
				fileset.delegate = delegate
				fileset.resolveStrategy = Closure.DELEGATE_FIRST
				fileset()
			}
			else {
	            fileset(dir:"${grailsHome}/lib") {
	                for(d in config.grails.war.dependencies) {
	                    include(name:d)
	                }
	                if(antProject.properties."ant.java.version" == "1.5") {
	                    for(d in config.grails.war.java5.dependencies) {
	                        include(name:d)
	                    }
	                }
	            }
	            if(antProject.properties."ant.java.version" == "1.4") {
	                fileset(dir:"${basedir}/lib/endorsed") {
	                        include(name:"*.jar")
	                }
	            }				
			}
		}                 
		Ant.copy(file:webXmlFile.absolutePath, tofile:"${stagingDir}/WEB-INF/web.xml")
		Ant.copy(file:log4jFile.absolutePath, tofile:"${stagingDir}/WEB-INF/log4j.properties")
        Ant.copy(todir:"${stagingDir}/WEB-INF/lib", flatten:true, failonerror:false) {
			fileset(dir:"${basedir}/plugins") {
                include(name:"*/lib/*.jar")
            }
        }
		  
	    Ant.propertyfile(file:"${stagingDir}/WEB-INF/classes/application.properties") {
	        entry(key:"grails.env", value:grailsEnv)
	        entry(key:"grails.war.deployed", value:"true")
	    }		
		
		Ant.replace(file:"${stagingDir}/WEB-INF/applicationContext.xml",
				token:"classpath*:", value:"" )
				
	    if(config.grails.war.resources) {
			def callable = config.grails.war.resources
			callable.delegate = Ant
			callable.resolveStrategy = Closure.DELEGATE_FIRST
			callable(stagingDir)
		}

		warPlugins()
		createDescriptor()
    	event("WarStart", ["Creating WAR ${warName}"])		
		Ant.jar(destfile:warName, basedir:stagingDir)
    	event("WarEnd", ["Created WAR ${warName}"])				
	}   
	finally {
		cleanUpAfterWar()
	}
    event("StatusFinal", ["Done creating WAR ${warName}"])
}                                                                    
  
target(createDescriptor:"Creates the WEB-INF/grails.xml file used to load Grails classes in WAR mode") {
     def resourceList = GrailsResourceLoaderHolder.resourceLoader.getResources()
     def pluginList = resolveResources("file:${basedir}/plugins/*/*GrailsPlugin.groovy")
      
	 new File("${stagingDir}/WEB-INF/grails.xml").withWriter { writer ->
		def xml = new groovy.xml.MarkupBuilder(writer)
		xml.grails {
			resources {
			   for(r in resourceList) {
				    def matcher = r.URL.toString() =~ /\S+?\/grails-app\/\S+?\/(\S+?).groovy/
					def name = matcher[0][1].replaceAll('/', /\./)
					resource(name)
			   } 
			}
			plugins {
                for(p in pluginList) {

                    def name = p.file.name - '.groovy'
                    plugin(name)
                }
            }
		}
	 }
	 
}

target(cleanUpAfterWar:"Cleans up after performing a WAR") {
	Ant.delete(dir:"${stagingDir}", failonerror:true)
}

target(warPlugins:"Includes the plugins in the WAR") {
	Ant.sequential {
		mkdir(dir:"${stagingDir}/WEB-INF/plugins")
		copy(todir:"${stagingDir}/WEB-INF/plugins", failonerror:false) {
			fileset(dir:"${basedir}/plugins")  {    
				include(name:"**/*plugin.xml")
			}
		}
	}
}

