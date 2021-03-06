package org.apache.mesos.logstash.scheduler;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "enable")
public class Features {

    private boolean collectd;

    public boolean isCollectd() {
        return collectd;
    }

    public void setCollectd(boolean collectd) {
        this.collectd = collectd;
    }
}
