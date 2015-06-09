package org.apache.mesos.logstash.scheduler;

import org.apache.commons.cli.BasicParser;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.log4j.Logger;
import org.apache.mesos.MesosSchedulerDriver;
import org.apache.mesos.Protos;
import org.apache.mesos.Protos.ContainerInfo.DockerInfo.PortMapping;
import org.apache.mesos.Scheduler;
import org.apache.mesos.SchedulerDriver;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Created by ero on 08/06/15.
 */
public class LogstashScheduler implements Scheduler, Runnable {

    public static final Logger LOGGER = Logger.getLogger(LogstashScheduler.class.toString());
    private static final int MESOS_PORT = 5050;
    private static final String FRAMEWORK_NAME = "LOGSTASH";
    public static final String TASK_DATE_FORMAT = "yyyyMMdd'T'HHmmss.SSS'Z'";

    private Clock clock = new Clock();
    private Set<Task> tasks = new HashSet<>();
    private String master;
    private String[] elasticNodes;

    // As per the DCOS Service Specification, setting the failover timeout to a large value;
    private static final double FAILOVER_TIMEOUT = 86400000;

    public LogstashScheduler(String master, String[] elasticNodes) {
        this.master = master;
        this.elasticNodes = elasticNodes;
    }

    public static void main(String[] args) {
        Options options = new Options();
        options.addOption("m", "master host or IP", true, "master host or IP");
        options.addOption("esnodes", "comma separated list of elastic nodes", true, "comma separated list of elastic nodes");
        CommandLineParser parser = new BasicParser();
        try {
            CommandLine cmd = parser.parse(options, args);
            String masterHost = cmd.getOptionValue("m");
//            String[] elsasticNodes = cmd.getOptionValue("esnodes").split(",");
            if (masterHost == null) {
                printUsage(options);
                return;
            }

            LOGGER.info("Starting Logstash on Mesos");
            final LogstashScheduler scheduler = new LogstashScheduler(masterHost, null);

            final Protos.FrameworkInfo.Builder frameworkBuilder = Protos.FrameworkInfo.newBuilder();
            frameworkBuilder.setUser("jclouds");
            frameworkBuilder.setName(FRAMEWORK_NAME);
            frameworkBuilder.setCheckpoint(true);
            frameworkBuilder.setFailoverTimeout(FAILOVER_TIMEOUT);

            final MesosSchedulerDriver driver = new MesosSchedulerDriver(scheduler, frameworkBuilder.build(), masterHost + ":" + MESOS_PORT);

            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                @Override
                public void run() {
                    driver.stop();
                    scheduler.onShutdown();
                }
            }));

            Thread schedThred = new Thread(scheduler);
            schedThred.start();
        } catch (ParseException e) {
            printUsage(options);
        }
    }

    private void onShutdown() {
        LOGGER.info("On shutdown...");
    }

    private static void printUsage(Options options) {
        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp(FRAMEWORK_NAME, options);
    }

    @Override
    public void run() {
        LOGGER.info("Starting up ...");
        SchedulerDriver driver = new MesosSchedulerDriver(this, Protos.FrameworkInfo.newBuilder().setUser("").setName(FRAMEWORK_NAME).build(), master + ":" + MESOS_PORT);
        driver.run();

    }

    @Override
    public void registered(SchedulerDriver schedulerDriver, Protos.FrameworkID frameworkID, Protos.MasterInfo masterInfo) {
        LOGGER.info("RESOURCE OFFER");
    }

    @Override
    public void reregistered(SchedulerDriver schedulerDriver, Protos.MasterInfo masterInfo) {

    }

    @Override
    public void resourceOffers(SchedulerDriver schedulerDriver, List<Protos.Offer> list) {
        LOGGER.info("RESOURCE OFFER");
        for (Protos.Offer offer : list) {

            if(tasks.size() == 0) {
                String id = taskId(offer);
                Protos.TaskInfo taskInfo = buildTask(schedulerDriver, offer.getResourcesList(), offer, id);
                schedulerDriver.launchTasks(Collections.singleton(offer.getId()), Collections.singleton(taskInfo));
                tasks.add(new Task(offer.getHostname(), id));
                schedulerDriver.launchTasks(Collections.singleton(offer.getId()), Collections.singleton(taskInfo));
            }else {
                schedulerDriver.declineOffer(offer.getId());
            }
        }
    }

    @Override
    public void offerRescinded(SchedulerDriver schedulerDriver, Protos.OfferID offerID) {

    }

    @Override
    public void statusUpdate(SchedulerDriver schedulerDriver, Protos.TaskStatus taskStatus) {

    }

    @Override
    public void frameworkMessage(SchedulerDriver schedulerDriver, Protos.ExecutorID executorID, Protos.SlaveID slaveID, byte[] bytes) {

    }

    @Override
    public void disconnected(SchedulerDriver schedulerDriver) {

    }

    @Override
    public void slaveLost(SchedulerDriver schedulerDriver, Protos.SlaveID slaveID) {

    }

    @Override
    public void executorLost(SchedulerDriver schedulerDriver, Protos.ExecutorID executorID, Protos.SlaveID slaveID, int i) {

    }

    @Override
    public void error(SchedulerDriver schedulerDriver, String s) {

    }

    private String taskId(Protos.Offer offer) {
        String date = new SimpleDateFormat(TASK_DATE_FORMAT).format(clock.now());
        return String.format("elasticsearch_%s_%s", offer.getHostname(), date);
    }

    private Integer selectFirstPort(List<Protos.Resource> offeredResources) {
        for (Protos.Resource resource : offeredResources) {
            if (resource.getType().equals(Protos.Value.Type.RANGES)) {
                return Integer.valueOf((int) resource.getRanges().getRangeList().get(0).getBegin());
            }
        }
        return null;
    }

    private void addAllScalarResources(List<Protos.Resource> offeredResources, List<Protos.Resource> acceptedResources) {
        for (Protos.Resource resource : offeredResources) {
            if (resource.getType().equals(Protos.Value.Type.SCALAR)) {
                acceptedResources.add(resource);
            }
        }
    }

    private Protos.TaskInfo buildTask(SchedulerDriver driver, List<Protos.Resource> offeredResources, Protos.Offer offer, String id) {


        List<Protos.Resource> acceptedResources = new ArrayList<>();

        addAllScalarResources(offeredResources, acceptedResources);

        Integer port = selectFirstPort(offeredResources);

        if (port == null) {
            LOGGER.info("Declined offer: Offer did not contain 1 port");
            driver.declineOffer(offer.getId());
        } else {
            LOGGER.info("Logstash transport port " + port);
            acceptedResources.add(Resources.singlePortRange(port));
        }

        Protos.TaskInfo.Builder taskInfoBuilder = Protos.TaskInfo.newBuilder()
                .setName(Configuration.TASK_NAME)
                .setTaskId(Protos.TaskID.newBuilder().setValue(id))
                .setSlaveId(offer.getSlaveId())
                .addAllResources(acceptedResources);


        LOGGER.info("Using Docker to start Logstash cloud mesos on slaves");
        Protos.ContainerInfo.Builder containerInfo = Protos.ContainerInfo.newBuilder();
        PortMapping transportPortMapping = PortMapping.newBuilder().setContainerPort(Configuration.LOGSTASH_TRANSPORT_PORT).setHostPort(port).build();


        Protos.ContainerInfo.DockerInfo.Builder docker = Protos.ContainerInfo.DockerInfo.newBuilder()
                .setNetwork(Protos.ContainerInfo.DockerInfo.Network.BRIDGE)
                .setImage("library/logstash")
                .addPortMappings(transportPortMapping);

        containerInfo.setDocker(docker.build());
        containerInfo.setType(Protos.ContainerInfo.Type.DOCKER);
        taskInfoBuilder.setContainer(containerInfo);
        taskInfoBuilder
                .setCommand(Protos.CommandInfo.newBuilder()
                        .addArguments("logstash")
                        .addArguments("-e")
                        .addArguments("input { file {\n" +
                                "    path => \"/etc/hosts\"\n" +
                                "    type => \"raw\"\n" +
                                "  } } output { stdout { } }")
                        .setShell(false))
                .build();

        LOGGER.info("Using Docker to start logstash cloud mesos on slaves");
        return taskInfoBuilder.build();
    }

    private InetAddress resolveHost(InetAddress masterAddress, String host) {
        try {
            masterAddress = InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            LOGGER.error("Could not resolve IP address for hostname " + host);
        }
        return masterAddress;
    }
}
