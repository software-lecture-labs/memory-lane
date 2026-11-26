package com.memorylane.backend.config;

import com.memorylane.backend.controller.JsonErrorReportValve;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.startup.Tomcat;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ML-18: installs {@link JsonErrorReportValve} as the host's error report valve. It has to be set
 * before the host starts, because the host creates its error report valve while starting; this
 * hook runs just before that. Spring Boot's server.* settings still apply to this factory.
 */
@Configuration(proxyBeanMethods = false)
public class TomcatConfiguration {
    @Bean
    TomcatServletWebServerFactory tomcatServletWebServerFactory() {
        return new TomcatServletWebServerFactory() {
            @Override
            protected TomcatWebServer getTomcatWebServer(Tomcat tomcat) {
                ((StandardHost) tomcat.getHost()).setErrorReportValveClass(JsonErrorReportValve.class.getName());
                return super.getTomcatWebServer(tomcat);
            }
        };
    }
}
