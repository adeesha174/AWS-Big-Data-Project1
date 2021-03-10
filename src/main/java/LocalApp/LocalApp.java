package LocalApp;

import java.io.*;
import java.util.*;

import Consts.Consts;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

public class LocalApp {
    private static final String localId = UUID.randomUUID().toString();
    private static String L2MQueueURL = "";

    // Creates sqs queue according to name
    // Returns the URL of the queue
    public static String createQueue(SqsClient sqs, String queueName) {
        // Creating a queue for messages from local application to manager
        Map<QueueAttributeName, String> attributes = new HashMap<>();
        attributes.put(QueueAttributeName.FIFO_QUEUE, "true");
        attributes.put(QueueAttributeName.CONTENT_BASED_DEDUPLICATION, "true");

        CreateQueueRequest createQueueRequest = CreateQueueRequest.builder()
                .queueName(queueName)
                .attributes(attributes)
                .build();

        sqs.createQueue(createQueueRequest);

        GetQueueUrlRequest getQueueRequest = GetQueueUrlRequest.builder()
                .queueName(queueName)
                .build();

        String queueURL = sqs.getQueueUrl(getQueueRequest).queueUrl();
        System.err.println("Queue created: " + queueURL);

        return queueURL;
    }

    // Requesting the URL of the given queueName
    public static void getQueueURL(SqsClient sqs, String queueName) {
        GetQueueUrlRequest getQueueRequest = GetQueueUrlRequest.builder()
                .queueName(queueName)
                .build();

        L2MQueueURL = sqs.getQueueUrl(getQueueRequest).queueUrl();
    }

    // Deletes sqs queue according to URL
    public static void deleteQueue(SqsClient sqs, String queueURL) {
        DeleteQueueRequest deleteQueueRequest = DeleteQueueRequest.builder()
                .queueUrl(queueURL)
                .build();

        sqs.deleteQueue(deleteQueueRequest);

        System.out.println("Queue deleted: " + queueURL);
    }

    // Sending a message to a queue
    public static void sendMessage(SqsClient sqs, String queueURL, String messageBody, String localId) {
        Map<String, MessageAttributeValue> attributes = new HashMap<>();
        attributes.put("clientId", MessageAttributeValue.builder().stringValue(localId).dataType("String").build());

        SendMessageRequest sendMsgRequest = SendMessageRequest.builder()
                .queueUrl(queueURL)
                .messageBody(messageBody)
                .messageGroupId("Group" + UUID.randomUUID().toString())
                .messageDeduplicationId("DeduplicationId" + UUID.randomUUID().toString())
                .messageAttributes(attributes)
                .delaySeconds(0)
                .build();

        sqs.sendMessage(sendMsgRequest);

        System.out.println("Message sent via: " + queueURL);
    }

    // Creates the manager instance
    // If the manager already exists- run it and return the id of the instance
    // Otherwise, creates a new instance tagged "Manager" and returns the new id
    public static String createManager(Ec2Client ec2, Tag tagManager) {
        String manager_id = "";

        /* .instanceType- The OS running to the computer
         * .imageID- The AMI string that we created (NEED TO CREATE!)
         * .maxCount and .minCount- The amount of computers we are asking for. For the manager- we need one,
         * for the workers- we decide according to the big data
         * .userData- What happens when the computer is on. The code in "shell" language that the computer
         * needs to run
         * .instanceInitiatedShutdownBehavior- What happens when the instance shuts down (terminate or close)
         */
        RunInstancesRequest managerRequest = RunInstancesRequest.builder()
                .instanceType(InstanceType.T2_MICRO)
                .imageId(Consts.amiManagerId)
                .maxCount(1)
                .minCount(1)
                .userData(Base64.getEncoder().encodeToString(Consts.managerBashCode.getBytes()))
                .iamInstanceProfile(IamInstanceProfileSpecification.builder().name("dsp211-ass1-role").build())
                .tagSpecifications(TagSpecification.builder().resourceType(ResourceType.INSTANCE).tags(tagManager).build())
                .build();

        try {
            RunInstancesResponse managerResponse = ec2.runInstances(managerRequest);
            manager_id = managerResponse.instances().get(0).instanceId();

            System.out.printf("Successfully started EC2 instance %s based on AMI %s",
                    manager_id,
                    Consts.amiManagerId);
        } catch (Ec2Exception e) {
            System.err.println(e.getMessage());
        }

        return manager_id;
    }

    // Checks if the manager exists, and if not creates one.
    public static String checkAndCreateManager(Ec2Client ec2) {
        String nextToken = null;
        String manager_id = "";

        Tag tagManager = Tag.builder()
                .key("Manager")
                .value("Manager")

                .build();

        do {
            DescribeInstancesRequest instancesRequest = DescribeInstancesRequest.builder().maxResults(6).nextToken(nextToken).build();
            DescribeInstancesResponse instancesResponse = ec2.describeInstances(instancesRequest);

            for (Reservation reservation : instancesResponse.reservations()) {
                for (Instance instance : reservation.instances()) {
                    if (instance.tags().contains(tagManager)) {
                        // Checking if the manager is running, if stopped- start it
                        StartInstancesRequest startManagerRequest;

                        switch (instance.state().code()) {
                            case 0: // Pending
                                System.out.println("Manager is pending");
                                manager_id = instance.instanceId();
                                break;
                            case 16: // Running
                                System.out.println("Manager is running");
                                manager_id = instance.instanceId();
                                break;
                            case 32:
                                System.out.println("Manager is shutting down");
                                // New one should be created anyway
                                break;
                            case 48:
                                System.out.println("Manager is terminated");
                                // New one should be created anyway
                                break;
                            case 64:
                                System.out.println("Manager is stopping");
                                manager_id = instance.instanceId();

                                startManagerRequest = StartInstancesRequest.builder()
                                        .instanceIds(manager_id)
                                        .build();

                                ec2.startInstances(startManagerRequest);
                                break;
                            case 80:
                                System.out.println("Manager is stopped");
                                manager_id = instance.instanceId();

                                startManagerRequest = StartInstancesRequest.builder()
                                        .instanceIds(manager_id)
                                        .build();

                                ec2.startInstances(startManagerRequest);
                                break;
                        }
                        if (!manager_id.equals(""))
                            break;
                    }
                }
                if (!manager_id.equals(""))
                    break;
            }
            nextToken = instancesResponse.nextToken();
        } while (nextToken != null);

        if (manager_id.equals(""))
            return createManager(ec2, tagManager);
        else {
            System.out.println("Manager already exists and now running: " + manager_id);
            return manager_id;
        }
    }

    // Putting an object on the s3 bucket given
    public static void putObject(S3Client s3, String bucketName, String fileKey, File file) {
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(fileKey)
                .build();

        // Turning the input file into a body for the s3
        RequestBody body = RequestBody.fromFile(file);
        s3.putObject(putObjectRequest, body);

        System.out.println("Object put on s3 on bucket: " + bucketName);
    }

    public static void main(String[] args) throws IOException {
        // ------------------------------------------------------------------------------------------------ //
        // -------------------------------------- Arguments management ------------------------------------ //

        final String USAGE =
                "To run this project, supply an input file name, output file name " +
                        "and the amount of images per worker (in this order).\n" +
                        "If you want the manager to terminate add the argument \"terminate\".";

        String shutDownBehavior = "";

        if (args.length == 3) {
            shutDownBehavior = "";
        }
        else if (args.length == 4) {
            shutDownBehavior = "terminate";
        }
        else {
            System.out.println(USAGE);
            System.exit(1);
        }

        // Setting up the parameter arguments
        String inputFileName = args[0];
        String outputFileName = args[1];
        String n = args[2];

        // Trying to open the input file. If not found, throw an exception and end the program
        File inputFile = new File(inputFileName);
        if (!inputFile.exists())
            throw new FileNotFoundException("Input file " + inputFileName + " not found");

        // ------------------------------------------------------------------------------------------------ //
        // -------------------------------- Creating s3, sqs and ec2 clients ------------------------------ //

        S3Client s3 = S3Client.builder()
                .region(Consts.region)
                .build();

        SqsClient sqs = SqsClient.builder()
                .region(Consts.region)
                .build();

        Ec2Client ec2 = Ec2Client.create();

        // ------------------------------------------------------------------------------------------------ //
        // ------------------------- Creating ec2 object and creating the manager ------------------------- //

        String manager_id = checkAndCreateManager(ec2);

        // ------------------------------------------------------------------------------------------------ //
        // -------------------------------------- Creating sqs queue ------------------------------------- //

        // Creating the queue from the manager to this local app
        // This is a unique queue name and URL
        String M2LQueueName = "dsp211-M2L-" + localId + ".fifo";
        String M2LqueueURL = createQueue(sqs, M2LQueueName);

        L2MQueueURL = createQueue(sqs, Consts.L2MqueueName);

        // ------------------------------------------------------------------------------------------------ //
        // ------------------------ Creating s3 object and uploading the input file ----------------------- //

        // Putting the input file in the bucket
        String inputFileKey = localId + "/" + inputFileName;
        String outputFileKey = localId + "/" + outputFileName;
        putObject(s3, Consts.s3BucketName, inputFileKey, inputFile);

        System.out.println("Input file put on bucket by local application");

        String initiateMessage = localId + "\n" +
                n + "\n" +
                inputFileKey + "\n" +
                outputFileKey + "\n" +
                M2LqueueURL;

        // Sending the manager the location of the input file
        sendMessage(sqs, L2MQueueURL, initiateMessage, localId);

        // ------------------------------------------------------------------------------------------------ //
        // ------------------------------------- Terminating the manager ---------------------------------- //

        // Sending the manager termination message, only if "terminated" is an argument
        if (shutDownBehavior.equals("terminate"))
            sendMessage(sqs, L2MQueueURL, "terminate", localId);

        // ------------------------------------------------------------------------------------------------ //
        // ------------------------------------- Waiting for the results ---------------------------------- //

        String fileKey = "";

        // Waiting for a message from the manager
        ReceiveMessageRequest receiveRequest = ReceiveMessageRequest.builder()
                .queueUrl(M2LqueueURL)
                .waitTimeSeconds(5)
                .maxNumberOfMessages(1)
                .messageAttributeNames("clientId")
                .build();

        int messagesCount = 0;
        while(messagesCount < 1) {
            List<Message> messagesFromManager = sqs.receiveMessage(receiveRequest).messages();
            messagesCount = messagesFromManager.size();

            if (messagesCount > 0) {
                Message managerMessage = messagesFromManager.get(0);

                System.out.println("Message received from manager: " + managerMessage.body());

                String inputLocationStr = managerMessage.body().substring(5);

                // Polling the input file from s3
                int slashPosition = inputLocationStr.indexOf('/');
                fileKey = inputLocationStr.substring(slashPosition + 1);
                System.out.println(Consts.s3BucketName + ", " + fileKey);
            }
        }

        // ------------------------------------------------------------------------------------------------ //
        // --------------------------------- Getting the result file from s3 ------------------------------ //

        // Getting the input file from the bucket
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(Consts.s3BucketName)
                .key(fileKey)
                .build();

        InputStream getObjectResponse = s3.getObject(getObjectRequest);
        BufferedReader br = new BufferedReader(new InputStreamReader(getObjectResponse));

        File outputFile = new File(outputFileName);
        FileWriter fw = new FileWriter(outputFile);
        BufferedWriter bw = new BufferedWriter(fw);

        String line;
        while((line = br.readLine()) != null) {
            bw.write(line + "\n");
        }

        bw.close();
        br.close();

        // ------------------------------------------------------------------------------------------------ //
        // --------------------------------------- Closing everything ------------------------------------- //

        // Deleting personal queue
        deleteQueue(sqs, M2LqueueURL);

        // Deleting the input file from the bucket
        s3.deleteObject(DeleteObjectRequest.builder()
                .bucket(Consts.s3BucketName)
                .key(inputFileKey)
                .build());

        // Deleting the output file from the bucket
        s3.deleteObject(DeleteObjectRequest.builder()
                .bucket(Consts.s3BucketName)
                .key(fileKey)
                .build());

        // ------------------------------------------------------------------------------------------------ //
        // -------------------------------------------- Finishing ----------------------------------------- //

        System.out.println(localId + "All done!");
    }
}
