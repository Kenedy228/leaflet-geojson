package com.github.gis;

import com.github.gis.diagnostics.logging.StartupDiagnostics;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class GisApplication {

  public static void main(String[] args) {
    var application = new SpringApplication(GisApplication.class);
    application.addListeners(new StartupDiagnostics());
    try {
      application.run(args);
    } catch (Throwable failure) {
      // The early listener has already emitted the categorical event. Never let the JVM print
      // an uncaught exception containing JDBC URLs, SQL or configuration values.
      System.exit(1);
    }
  }
}
