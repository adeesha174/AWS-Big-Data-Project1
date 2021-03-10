package Worker;

import Consts.Consts;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URL;
import java.util.*;

public class Worker {
    private static String M2WqueueURL = "";
    private static String W2MqueueURL = "";

    // Requesting the URL of the given queueName
    public static void getQueueURL(SqsClient sqs, String queueName) {
        GetQueueUrlRequest getQueueRequest = GetQueueUrlRequest.builder()
                .queueName(queueName)
                .build();

        if (queueName.equals("dsp211-M2W-ass1.fifo"))
            M2WqueueURL = sqs.getQueueUrl(getQueueRequest).queueUrl();
        else
            W2MqueueURL = sqs.getQueueUrl(getQueueRequest).queueUrl();
    }

    // Running OCR using tesseract on the image from the given URL
    public static String run_ocr(Tesseract tesseract, String url) throws TesseractException, IOException {
        BufferedImage image;

        URL url2 = new URL(url); // Downloading the image
        image = ImageIO.read(url2);
        if (image != null)
            return tesseract.doOCR(image); // Running OCR
        else
            throw new IOException();
    }

    // Sending a message to a queue
    public static void sendMessage(SqsClient sqs, String queueURL, String messageBody, String localId) {
        Map<String, MessageAttributeValue> attributes = new HashMap<>();
        attributes.put("clientId", MessageAttributeValue.builder().stringValue(localId).dataType("String").build());

        SendMessageRequest sendMsgRequest = SendMessageRequest.builder()
                .queueUrl(queueURL)
                .messageBody(messageBody)
                .messageGroupId("Group" + UUID.randomUUID().toString())
                .messageDeduplicationId("Deduplication" + UUID.randomUUID().toString())
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

    public static void main(String[] args) {
        // ------------------------------------------------------------------------------------------------ //
        // ------------------------------------------ Declarations ---------------------------------------- //

        Tesseract tesseract = new Tesseract();

        // ------------------------------------------------------------------------------------------------ //
        // -------------------------------------- Creating sqs clients ------------------------------------ //

        SqsClient sqs = SqsClient.builder()
                .region(Consts.region)
                .build();

        // ------------------------------------------------------------------------------------------------ //
        // -------------------------------------------- Others -------------------------------------------- //

        String m2WqueueName = "dsp211-M2W-ass1.fifo";
        getQueueURL(sqs, m2WqueueName);
        String w2MqueueName = "dsp211-W2M-ass1.fifo";
        getQueueURL(sqs, w2MqueueName);

        // ------------------------------------------------------------------------------------------------ //
        // ----------------------------------- Receiving messages from sqs -------------------------------- //

        while(true) try {
            // Receiving the message from the manager
            List<Message> URLmessages = receiveMessage(sqs, M2WqueueURL, 1);

            System.out.println("Messages size: " + URLmessages.size());

            if (URLmessages.size() > 0) {
                System.out.println("GotHere!");

                Message msg = URLmessages.get(0);
                String localId = extractAttribute(msg, "clientId");

                System.out.println("LocalID: " + localId);

                String content = msg.body();

                StringBuilder result = new StringBuilder(content);

                try {
                    String text = run_ocr(tesseract, content);
                    result.append("\n").append(text);
                }
                catch (IOException e) { // Downloading the image failed
                    result.append("\n");
                    result.append(": input file ").append(content).append(" cannot be found! Maybe the link is broken?\n");

                    System.out.println(": input file " + content + " cannot be found! Maybe the link is broken?\n");
                }
                catch (TesseractException e) { // Performing OCR failed
                    result.append("\n");
                    result.append(": input file cannot run OCR on the image\n");

                    System.out.println(": input file cannot run OCR on the image\n");
                }

                // Sending the manager a message with the url and ocr
                sendMessage(sqs, W2MqueueURL, result.toString(), localId);

                // Deleting the message from the queue
                deleteMessage(sqs, M2WqueueURL, msg);
            }
        }
        catch(Exception e) {
            System.out.println(e.getMessage());
        }
    }
}
