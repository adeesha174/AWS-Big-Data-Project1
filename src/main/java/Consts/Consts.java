package Consts;

import software.amazon.awssdk.regions.Region;

public class Consts {
    public static final String L2MqueueName = "dsp211-L2M-ass1.fifo";
    public static final String M2WqueueName = "dsp211-M2W-ass1.fifo";
    public static final String W2MqueueName = "dsp211-W2M-ass1.fifo";

    public static final String s3JarBucket = "s3://jar-bucket-ass1/";

    public static final String s3BucketName = "dsp211-bucket-ass1";

    public static final String amiManagerId = "ami-03db209d213f967c4";
    public static final String amiWorkerId = "ami-0b5ed905b5cfb9bf6";

    public static final Region region = Region.US_EAST_1;

    public static final String managerBashCode =
            "#!/bin/bash\n" +
                    "aws s3 cp " + s3JarBucket + "manager.jar /home/ubuntu/manager.jar\n" +
                    "cd /home/ubuntu/\n" +
                    "java -jar manager.jar\n";

    public static final String workerBashCode =
            "#!/bin/bash\n" +
                    "export TESSDATA_PREFIX=/usr/share/tesseract-ocr/4.00/tessdata\n" +
                    "aws s3 cp " + s3JarBucket + "worker.jar /home/ubuntu/worker.jar\n" +
                    "cd /home/ubuntu/\n" +
                    "java -jar worker.jar\n";
}
