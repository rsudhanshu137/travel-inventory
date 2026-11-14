package com.example.travel;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Local-only helper: starts H2's own web console on :8082 inside the app JVM. Off unless travel.h2-web-console=true. */
@Configuration
@ConditionalOnProperty(name = "travel.h2-web-console", havingValue = "true")
public class H2WebConsoleConfig {

    @Bean
    public AutoCloseable h2WebConsole() throws Exception {
        Class<?> serverClass = Class.forName("org.h2.tools.Server");
        Object server = serverClass.getMethod("createWebServer", String[].class)
                .invoke(null, (Object) new String[] {"-web", "-webPort", "8082"});
        serverClass.getMethod("start").invoke(server);
        return () -> serverClass.getMethod("stop").invoke(server);
    }
}
