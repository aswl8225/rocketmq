package org.apache.rocketmq.example.quickstart;

import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.remoting.common.RemotingHelper;

public class Producer1 {
    public static void main(String[] args) throws MQClientException, InterruptedException {

        /*
         * Instantiate with a producer group name.
         */
        DefaultMQProducer producer = new DefaultMQProducer("producerGroup1");
        producer.setNamesrvAddr("localhost:9876");

        /*
         * Specify name server addresses.
         * <p/>
         *
         * Alternatively, you may specify name server addresses via exporting environmental variable: NAMESRV_ADDR
         * <pre>
         * {@code
         * producer.setNamesrvAddr("name-server1-ip:9876;name-server2-ip:9876");
         * }
         * </pre>
         */

        /*
         * Launch the instance.
         */
        producer.start();

        for (int i = 0; i < Integer.MAX_VALUE; i++) {
            try {

                /*
                 * Create a message instance, specifying topic, tag and message body.
                 */
                Message msg = new Message("DefaultCluster" /* Topic */,
                        "tagA" /* Tag */,
                        ("Hello RocketMQHello RocketMQHello RocketMQHello RocketMQHello RocketMQHello RocketMQHello RocketMQHello RocketMQHello " +
//                                "RocketMQHello RocketMQHello RocketMQHello RocketMQHello RocketMQHello RocketMQHello RocketMQHello RocketMQHello" +
//                                " RocketMQHello RocketMQHello RocketMQHello RocketMQHello RocketMQHello RocketMQHello RocketMQHello RocketMQHello" +
//                                " RocketMQHello RocketMQHello RocketMQHello RocketMQHello RocketMQHello RocketMQHello RocketMQHello RocketMQHello" +

                                "RocketMQHello RocketMQHello RocketMQHello RocketMQHello RocketMQHello RocketMQ " + i).getBytes(RemotingHelper.DEFAULT_CHARSET) /* Message body */
                );
                msg.setKeys(System.currentTimeMillis()+"");

                /*
                 * Call send message to deliver message to one of brokers.
                 */
                SendResult sendResult = producer.send(msg);

                System.out.printf("%s%n", sendResult);
            } catch (Exception e) {
                e.printStackTrace();
                Thread.sleep(1000);
            }
        }

        Thread.sleep(Integer.MAX_VALUE);
        /*
         * Shut down once the producer instance is not longer in use.
         */
        producer.shutdown();
    }
}
