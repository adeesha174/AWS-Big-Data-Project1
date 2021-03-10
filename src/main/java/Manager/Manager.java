package Manager;

import Consts.Consts;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class Manager {
    private static String L2MqueueURL = "";
    private static String M2WqueueURL = "";
    private static String W2MqueueURL = "";
    private static int workersCount = 0;
    private static List<String> workersIds = new ArrayList<>();
    private static List<String> localIds = new ArrayList<>();
    private static AtomicBoolean shouldTerminate = new AtomicBoolean(false);

    private static synchronized void removeFromList(List<String> list, String obj) {
        list.remove(obj);

        System.out.println("Object removed from list: " + obj);
    }

    private static synchronized void addToList(List<String> list, List<String> toAdd) {
        list.addAll(toAdd);

        System.out.println("Items added to list");
    }

    // Returns the ID of the manager (this instance)
    public static String getManagerId(Ec2Client ec2) {
        String nextToken = null;
        String managerId = "";

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

                        if (instance.state().code() == 16) {
                            System.out.println("Manager is running");
                            managerId = instance.instanceId();
                        }
                        if (!managerId.equals(""))
                            break;
                    }
                }
                if (!managerId.equals(""))
                    break;
            }
            nextToken = instancesResponse.nextToken();
        } while (nextToken != null);

        return managerId;
    }

    // Terminating the manager instance
    public static void terminateManager(Ec2Client ec2) {
        List<String> managerListForTermination = new ArrayList<>();
        managerListForTermination.add(getManagerId(ec2));
        terminateInstances(ec2, managerListForTermination);
    }

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

    // Writing imageURL and it's OCR content to the output file
    public static void writeImages(BufferedWriter outputFile, String imageURL, String ocrText) {
        StringBuilder result = new StringBuilder();
        String str = "<img src=\"" + imageURL + "\">\n" +
                "<br>" + ocrText + "<br><br>\n";
        result.append(str);
        result.append("\n");

        try {
            outputFile.write(result.toString());
        }
        catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Initiating the output file with the header of an HTML file
    public static void initiateFile(BufferedWriter outputFile) {
        if (outputFile != null) {
            try {
                outputFile.write("<html>\n" +
                        "<body>\n\n");
            }
            catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    // Requesting the URL of the given queueName
    public static void getQueueURL(SqsClient sqs, String queueName) {
        if (queueName.equals(Consts.L2MqueueName)) {
            GetQueueUrlRequest getQueueRequest = GetQueueUrlRequest.builder()
                    .queueName(queueName)
                    .build();

            L2MqueueURL = sqs.getQueueUrl(getQueueRequest).queueUrl();
        }
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

    // Receiving a number of messages from the given queue
    public static List<Message> receiveMessage(SqsClient sqs, String queueURL, int maxNumerOfMessages) {
        ReceiveMessageRequest receiveMessageRequest = ReceiveMessageRequest.builder()
                .queueUrl(queueURL)
                .waitTimeSeconds(5)
                .maxNumberOfMessages(maxNumerOfMessages)
                .messageAttributeNames("clientId")
                .build();

        List<Message> messages = sqs.receiveMessage(receiveMessageRequest).messages();

        System.out.println("Message received via: " + queueURL + ", amount of messages: " + messages.size());

        return messages;
    }

    // Deleting a message from the given queue
    public static void deleteMessage(SqsClient sqs, String queueURL, Message msg) {
        DeleteMessageRequest deleteMessageRequest = DeleteMessageRequest.builder()
                .queueUrl(queueURL)
                .receiptHandle(msg.receiptHandle())
                .build();

        sqs.deleteMessage(deleteMessageRequest);

        System.out.println("Message deleted via: " + queueURL);
    }

    // Extracts the given attribute from the given message
    public static String extractAttribute(Message msg, String attribute) {
        Map<String, MessageAttributeValue> msgAttributes = msg.messageAttributes();
        MessageAttributeValue attributeValue = msgAttributes.get(attribute);
        if (attributeValue != null)
            return attributeValue.stringValue();
        else
            return "";
    }

    // Creates amount of instances and tags them to be workers
    // Returns a list of the instances ids created
    public static List<String> createInstances(Ec2Client ec2, int amount) {
        List<Instance> instances;
        List<String> instancesIds = new ArrayList<>();

        Tag tagWorker = Tag.builder()
                .key("Worker")
                .value("Worker")
                .build();

        RunInstancesRequest workerRequest = RunInstancesRequest.builder()
                .instanceType(InstanceType.T2_MICRO)
                .imageId(Consts.amiWorkerId)
                .minCount(1)
                .maxCount(amount)
                .userData(Base64.getEncoder().encodeToString(Consts.workerBashCode.getBytes()))
                .iamInstanceProfile(IamInstanceProfileSpecification.builder().name("dsp211-ass1-role").build())
                .tagSpecifications(TagSpecification.builder().resourceType(ResourceType.INSTANCE).tags(tagWorker).build())
                .build();

        try {
            RunInstancesResponse workerResponse = ec2.runInstances(workerRequest);
            instances = workerResponse.instances();

            for (Instance instance : instances)
                instancesIds.add(instance.instanceId());

            workersCount += instances.size();
        } catch (Ec2Exception e) {
            e.printStackTrace();
        }

        return instancesIds;
    }

    // Calculating how many workers to create
    // m = job size / n
    private static synchronized List<String> createWorkers(Ec2Client ec2, int m) {
        // The maximum amount of running instances is 20
        if (m == 0)
            m = 1;
        else if (m >= 15)
            m = 15;

        // Create the amount of workers needed according to the current amount of running instances
        if (m - workersCount > 0) {
            List<String> newInstances = createInstances(ec2, m - workersCount);
            try {
                Thread.sleep(1000); // Let the new workers be known before next iteration
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return newInstances;
        }
        return null;
    }

    // Terminates the given instances (given by their IDs)
    public static void terminateInstances(Ec2Client ec2, List<String> instancesIds) {
        TerminateInstancesRequest terminateInstancesRequest = TerminateInstancesRequest.builder()
                .instanceIds(instancesIds)
                .build();

        ec2.terminateInstances(terminateInstancesRequest);

        workersCount -= instancesIds.size();

        System.out.println("Workers are now terminated");
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

    // Deletes sqs queue according to URL
    public static void deleteQueue(SqsClient sqs, String queueURL) {
        DeleteQueueRequest deleteQueueRequest = DeleteQueueRequest.builder()
                .queueUrl(queueURL)
                .build();

        sqs.deleteQueue(deleteQueueRequest);

        System.out.println("Queue deleted: " + queueURL);
    }

    public static void main(String[] args) {
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
        // ----------------------------------------- Queues URLs ------------------------------------------ //

        getQueueURL(sqs, Consts.L2MqueueName);
        M2WqueueURL = createQueue(sqs, Consts.M2WqueueName);
        W2MqueueURL = createQueue(sqs, Consts.W2MqueueName);

        // ------------------------------------------------------------------------------------------------ //
        // ------------------------------------- Starting the threads ------------------------------------- //

        // The thread waiting for a tarmination message
        new Thread(() -> {
            while (!shouldTerminate.get() || !localIds.isEmpty()) try {
                Thread.sleep(2000);
            }
            catch(Exception e) {
                e.printStackTrace();
            }

            // Deleting the queues
            deleteQueue(sqs, L2MqueueURL);
            deleteQueue(sqs, M2WqueueURL);
            deleteQueue(sqs, W2MqueueURL);

            // Terminating all instances
            terminateInstances(ec2, workersIds);
            terminateManager(ec2);
        }).start();

        // The main threads performing local apps jobs
        while (!shouldTerminate.get()) {
            // Receiving the message from the local application
            ReceiveMessageRequest receiveMessageRequest = ReceiveMessageRequest.builder()
                    .queueUrl(L2MqueueURL)
                    .waitTimeSeconds(1)
                    .maxNumberOfMessages(1)
                    .messageAttributeNames("clientId")
                    .build();

            Message message = null;
            while (message == null) {
                System.out.println("No messages from local apps.. trying again");
                List<Message> msgs = sqs.receiveMessage(receiveMessageRequest).messages();
                if (msgs.size() > 0)
                    message = msgs.get(0);
            }

            System.out.println("Message received: " + message.body());

            // Deleting the message from the queue
            deleteMessage(sqs, L2MqueueURL, message);

            if (!message.body().equals("terminate")) {
                // Analyzing the message
                String[] info = message.body().split("\n");
                final String localId = info[0];
                final int n = Integer.parseInt(info[1]);
                final String inputFileKey = info[2];
                final String outputFileKey = info[3];
                final String M2LqueueURL = info[4];

                // Adding the currect local app to the list (synchronized)
                List<String> tempLocalAppsList = new ArrayList<>();
                tempLocalAppsList.add(localId);
                addToList(localIds, tempLocalAppsList);

                new Thread(() -> {
                    // Polling the input file from s3
                    GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                            .bucket(Consts.s3BucketName)
                            .key(inputFileKey)
                            .build();

                    InputStream getObjectResponse = s3.getObject(getObjectRequest);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(getObjectResponse));

                    // Calculating the amount of urls given
                    int numOfLines = 0;
                    List<String> URLs = new ArrayList<>();
                    String line;

                    try {
                        while ((line = reader.readLine()) != null) {
                            numOfLines++;
                            URLs.add(line);
                        }
                    } catch (IOException ex) {
                        ex.printStackTrace();
                    }

                    System.out.println("Num of lines: " + numOfLines);

                    // Creating the workers
                    List<String> instancesIds = createWorkers(ec2, numOfLines / n);
                    if(instancesIds != null) {
                        List<String> tempWorkersList = new ArrayList<>(instancesIds);
                        addToList(workersIds, tempWorkersList);
                    }

                    // Sending the urls messages to the workers
                    for (String imageURL : URLs)
                        sendMessage(sqs, M2WqueueURL, imageURL, localId);

                    // Creating an HTML file with the images and their texts
                    try {
                        File outputFile = new File("outputFileManager.txt");

                        FileWriter fw = new FileWriter(outputFile);
                        BufferedWriter bw = new BufferedWriter(fw);

                        initiateFile(bw);

                        // Reading the messages from the workers
                        int numOfMessagesFromWorkers = 0;
                        while(numOfMessagesFromWorkers < numOfLines) try {
                            // Receiving the messages from the local application
                            List<Message> workerMessages = receiveMessage(sqs, W2MqueueURL, 1);

                            if (workerMessages.size() > 0) {
                                // Each message is the url of the image and the ocr text
                                Message msg = workerMessages.get(0);

                                // Checking if the message belongs to this local app
                                if(!localId.equals(extractAttribute(msg, "clientId")))
                                    continue;

                                String content = msg.body();
                                int firstIndex = content.indexOf('\n');
                                String imageURL = content.substring(0, firstIndex);
                                String ocrResult = content.substring(firstIndex + 1);

                                // Adding the data to the output file
                                writeImages(bw, imageURL, ocrResult);

                                // Deleting the message from the queue
                                deleteMessage(sqs, W2MqueueURL, msg);

                                numOfMessagesFromWorkers++;
                            }
                        }
                        catch(Exception e) {
                            System.out.println("Exception occured in manager..");
                        }

                        bw.write("\n</body>\n" +
                                "</html>");
                        bw.close();

                        // Uploading the out pot file to s3 for the local application
                        putObject(s3, Consts.s3BucketName, outputFileKey, outputFile);
                    }
                    catch (IOException e) {
                        e.printStackTrace();
                    }

                    // Removing the currect local app from the list
                    removeFromList(localIds, localId);

                    // Sending the local app the location of the
                    sendMessage(sqs, M2LqueueURL, "s3://" + Consts.s3BucketName + "/" + outputFileKey, localId);
                }).start();
            }
            else
                shouldTerminate.set(true);
        }

        // Waiting for termination
        while(true);
    }
}
