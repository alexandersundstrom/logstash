package org.apache.mesos.logstash.executor;

import org.apache.mesos.logstash.common.LogType;
import org.apache.mesos.logstash.executor.docker.ContainerizerClient;
import org.apache.mesos.logstash.executor.docker.DockerLogSteamManager;
import org.apache.mesos.logstash.executor.frameworks.DockerFramework;
import org.apache.mesos.logstash.executor.frameworks.Framework;
import org.apache.mesos.logstash.executor.frameworks.FrameworkInfo;
import org.apache.mesos.logstash.executor.frameworks.HostFramework;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Class responsible for connecting each discovered framework to logstash
 */
public class ConfigManager implements ConfigEventListener {

    public static final Logger LOGGER = LoggerFactory.getLogger(ConfigManager.class);

    private LogstashService logstash;
    private ContainerizerClient containerizerClient;
    private DockerLogSteamManager dockerLogSteamManager;
    public List<FrameworkInfo> dockerInfos = new ArrayList<>();

    public ConfigManager(ContainerizerClient containerizerClient, LogstashService logstash,
        DockerLogSteamManager dockerLogSteamManager) {
        this.containerizerClient = containerizerClient;
        this.logstash = logstash;
        this.dockerLogSteamManager = dockerLogSteamManager;
        this.containerizerClient.setDelegate(this::onContainerListUpdated);
    }

    @Override
    public void onConfigUpdated(LogType type, Stream<FrameworkInfo> info) {
        LOGGER.info("New config received. Reconfiguring.");
        switch (type) {
            case HOST:
                updateHost(info);
                break;
            case DOCKER:
                updateDocker(info);
                break;
        }
    }

    private void onContainerListUpdated(List<String> images) {
        LOGGER.info("New containers discovered. Reconfiguring.");
        reconfigureDockerLogs(dockerInfos.stream());
    }

    private void reconfigureDockerLogs(Stream<FrameworkInfo> logstashInfos) {

        // On new configs received or new containers

        // - Find running containers that have a matching config.

        Function<String, FrameworkInfo> lookupConfig = createLookupHelper(logstashInfos);

        Predicate<String> hasKnownConfig = c -> lookupConfig.apply(c) != null;
        Function<String, DockerFramework> createFramework = c -> new DockerFramework(
            lookupConfig.apply(c), new DockerFramework.ContainerId(c));

        Stream<DockerFramework> frameworks = containerizerClient
            .getRunningContainers()
            .stream()
            .filter(hasKnownConfig)
            .map(createFramework);

        // - For each new running container start streaming logs.

        frameworks = frameworks.peek(dockerLogSteamManager::setupContainerLogfileStreaming);

        // - TODO: For each changed config, stop streaming and reconfigure.

        // - Compile new config (with all local versions of container logs) and place in config folder.

        String config = frameworks
            .map(Framework::getConfiguration)
            .collect(Collectors.joining("\n"));

        // - Restart the LogStash process.

        logstash.updateConfig(LogType.DOCKER, config);
    }

    private void updateHost(Stream<FrameworkInfo> logstashInfos) {
        String config = logstashInfos.map(HostFramework::new)
            .map(Framework::getConfiguration)
            .collect(Collectors.joining("\n"));

        logstash.updateConfig(LogType.HOST, config);
    }

    private void updateDocker(Stream<FrameworkInfo> logstashInfos) {
        // Make a local copy so that we can immediately reconfigure if we discover new containers!
        dockerInfos = logstashInfos.collect(Collectors.toList());
        reconfigureDockerLogs(dockerInfos.stream());
    }

    private Function<String, FrameworkInfo> createLookupHelper(
        Stream<FrameworkInfo> logstashInfos) {
        // TODO we need to make this helper more generic, so that a prefix/suffix match is also valid
        // i.e if the config name is a prefix or suffix of a containers image name
        Map<String, FrameworkInfo> logstashInfoMap = logstashInfos
            .collect(Collectors.toMap(FrameworkInfo::getName, x -> x));
        return c -> logstashInfoMap.get(containerizerClient.getImageNameOfContainer(c));
    }
}