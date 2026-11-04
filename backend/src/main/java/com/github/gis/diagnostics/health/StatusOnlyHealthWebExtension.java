package com.github.gis.diagnostics.health;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.actuate.endpoint.ApiVersion;
import org.springframework.boot.actuate.endpoint.web.annotation.EndpointWebExtension;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthContributorRegistry;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.HealthEndpointGroups;
import org.springframework.boot.actuate.health.HealthEndpointWebExtension;
import org.springframework.boot.actuate.health.StatusAggregator;
import org.springframework.stereotype.Component;

@Component
@EndpointWebExtension(endpoint = HealthEndpoint.class)
public class StatusOnlyHealthWebExtension extends HealthEndpointWebExtension {
  public StatusOnlyHealthWebExtension(
      HealthContributorRegistry registry, HealthEndpointGroups groups) {
    super(registry, groups, Duration.ofSeconds(10));
  }

  @Override
  protected HealthComponent aggregateContributions(
      ApiVersion version,
      Map<String, HealthComponent> contributions,
      StatusAggregator aggregator,
      boolean showComponents,
      Set<String> groupNames) {
    var aggregate =
        super.aggregateContributions(
            version, contributions, aggregator, showComponents, groupNames);
    // Retain Boot's status/HTTP mapping while removing group labels as well as component details.
    return Health.status(aggregate.getStatus()).build();
  }
}
