/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.store.dledger;

import io.openmessaging.storage.dledger.AppendFuture;
import io.openmessaging.storage.dledger.DLedgerConfig;
import io.openmessaging.storage.dledger.DLedgerServer;
import io.openmessaging.storage.dledger.entry.DLedgerEntry;
import io.openmessaging.storage.dledger.protocol.AppendEntryRequest;
import io.openmessaging.storage.dledger.protocol.AppendEntryResponse;
import io.openmessaging.storage.dledger.protocol.DLedgerResponseCode;
import io.openmessaging.storage.dledger.store.file.DLedgerMmapFileStore;
import io.openmessaging.storage.dledger.store.file.MmapFile;
import io.openmessaging.storage.dledger.store.file.MmapFileList;
import io.openmessaging.storage.dledger.store.file.SelectMmapBufferResult;
import io.openmessaging.storage.dledger.utils.DLedgerUtils;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.apache.rocketmq.common.UtilAll;
import org.apache.rocketmq.common.message.MessageAccessor;
import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.common.message.MessageDecoder;
import org.apache.rocketmq.common.message.MessageExtBatch;
import org.apache.rocketmq.common.sysflag.MessageSysFlag;
import org.apache.rocketmq.common.topic.TopicValidator;
import org.apache.rocketmq.store.AppendMessageResult;
import org.apache.rocketmq.store.AppendMessageStatus;
import org.apache.rocketmq.store.CommitLog;
import org.apache.rocketmq.store.DefaultMessageStore;
import org.apache.rocketmq.store.DispatchRequest;
import org.apache.rocketmq.store.MappedFile;
import org.apache.rocketmq.store.MessageExtBrokerInner;
import org.apache.rocketmq.store.PutMessageResult;
import org.apache.rocketmq.store.PutMessageStatus;
import org.apache.rocketmq.store.SelectMappedBufferResult;
import org.apache.rocketmq.store.StoreStatsService;
import org.apache.rocketmq.store.schedule.ScheduleMessageService;

/**
 * Store all metadata downtime for recovery, data protection reliability
 */

/**
 * magic              4     模
 * size               4     消息大小
 * index              8     存储消息顺序
 * term               8     raft选举周期
 * pos                8     offset
 * channel            4
 * chainCrc           4
 * bodyCrc            4
 * bodyLength         4     消息体大小
 * body               ?     消息体内容-commitLog
 */
public class DLedgerCommitLog extends CommitLog {
    private final DLedgerServer dLedgerServer;
    private final DLedgerConfig dLedgerConfig;
    private final DLedgerMmapFileStore dLedgerFileStore;
    private final MmapFileList dLedgerFileList;

    //The id identifies the broker role, 0 means master, others means slave
    private final int id;

    private final MessageSerializer messageSerializer;
    private volatile long beginTimeInDledgerLock = 0;

    //This offset separate the old commitlog from dledger commitlog
    /**
     * dividedCommitlogOffset之前的消息都是commitlog
     * 之后的消息都是dledgerCommitlog
     */
    private long dividedCommitlogOffset = -1;

    private boolean isInrecoveringOldCommitlog = false;

    public DLedgerCommitLog(final DefaultMessageStore defaultMessageStore) {
        super(defaultMessageStore);
        dLedgerConfig = new DLedgerConfig();
        dLedgerConfig.setEnableDiskForceClean(defaultMessageStore.getMessageStoreConfig().isCleanFileForciblyEnable());
        dLedgerConfig.setStoreType(DLedgerConfig.FILE);
        dLedgerConfig.setSelfId(defaultMessageStore.getMessageStoreConfig().getdLegerSelfId());
        dLedgerConfig.setGroup(defaultMessageStore.getMessageStoreConfig().getdLegerGroup());
        dLedgerConfig.setPeers(defaultMessageStore.getMessageStoreConfig().getdLegerPeers());
        dLedgerConfig.setStoreBaseDir(defaultMessageStore.getMessageStoreConfig().getStorePathRootDir());
        dLedgerConfig.setMappedFileSizeForEntryData(defaultMessageStore.getMessageStoreConfig().getMappedFileSizeCommitLog());
        dLedgerConfig.setDeleteWhen(defaultMessageStore.getMessageStoreConfig().getDeleteWhen());
        dLedgerConfig.setFileReservedHours(defaultMessageStore.getMessageStoreConfig().getFileReservedTime() + 1);
        id = Integer.valueOf(dLedgerConfig.getSelfId().substring(1)) + 1;
        dLedgerServer = new DLedgerServer(dLedgerConfig);
        dLedgerFileStore = (DLedgerMmapFileStore) dLedgerServer.getdLedgerStore();
        /**
         * dledger下   回填存储的commitlog的PHYSICALOFFSET
         */
        DLedgerMmapFileStore.AppendHook appendHook = (entry, buffer, bodyOffset) -> {
            assert bodyOffset == DLedgerEntry.BODY_OFFSET;
            buffer.position(buffer.position() + bodyOffset + MessageDecoder.PHY_POS_POSITION);
            buffer.putLong(entry.getPos() + bodyOffset);
        };
        dLedgerFileStore.addAppendHook(appendHook);
        dLedgerFileList = dLedgerFileStore.getDataFileList();
        this.messageSerializer = new MessageSerializer(defaultMessageStore.getMessageStoreConfig().getMaxMessageSize());

    }

    @Override
    public boolean load() {
        return super.load();
    }

    private void refreshConfig() {
        dLedgerConfig.setEnableDiskForceClean(defaultMessageStore.getMessageStoreConfig().isCleanFileForciblyEnable());
        dLedgerConfig.setDeleteWhen(defaultMessageStore.getMessageStoreConfig().getDeleteWhen());
        dLedgerConfig.setFileReservedHours(defaultMessageStore.getMessageStoreConfig().getFileReservedTime() + 1);
    }

    private void disableDeleteDledger() {
        dLedgerConfig.setEnableDiskForceClean(false);
        dLedgerConfig.setFileReservedHours(24 * 365 * 10);
    }

    @Override
    public void start() {
        /**
         * dLedgerServer启动
         */
        dLedgerServer.startup();
    }

    @Override
    public void shutdown() {
        dLedgerServer.shutdown();
    }

    @Override
    public long flush() {
        dLedgerFileStore.flush();
        return dLedgerFileList.getFlushedWhere();
    }

    /**
     * 获得最大得offset
     *
     * @return
     */
    @Override
    public long getMaxOffset() {
        if (dLedgerFileStore.getCommittedPos() > 0) {
            return dLedgerFileStore.getCommittedPos();
        }
        if (dLedgerFileList.getMinOffset() > 0) {
            return dLedgerFileList.getMinOffset();
        }
        return 0;
    }

    /**
     * 获取commitlog最小且有效得offset
     *
     * @return
     */
    @Override
    public long getMinOffset() {
        /**
         * 获取第一个commitlog对应的起始offset
         */
        if (!mappedFileQueue.getMappedFiles().isEmpty()) {
            return mappedFileQueue.getMinOffset();
        }
        //FileFromOffset + StartPosition
        return dLedgerFileList.getMinOffset();
    }

    /**
     * dledger下   最大的offset就是ConfirmOffset
     *
     * @return
     */
    @Override
    public long getConfirmOffset() {
        return this.getMaxOffset();
    }

    @Override
    public void setConfirmOffset(long phyOffset) {
        log.warn("Should not set confirm offset {} for dleger commitlog", phyOffset);
    }

    @Override
    public long remainHowManyDataToCommit() {
        return dLedgerFileList.remainHowManyDataToCommit();
    }

    @Override
    public long remainHowManyDataToFlush() {
        return dLedgerFileList.remainHowManyDataToFlush();
    }

    @Override
    public int deleteExpiredFile(
        final long expiredTime,
        final int deleteFilesInterval,
        final long intervalForcibly,
        final boolean cleanImmediately
    ) {
        if (mappedFileQueue.getMappedFiles().isEmpty()) {
            refreshConfig();
            //To prevent too much log in defaultMessageStore
            return Integer.MAX_VALUE;
        } else {
            disableDeleteDledger();
        }
        int count = super.deleteExpiredFile(expiredTime, deleteFilesInterval, intervalForcibly, cleanImmediately);
        if (count > 0 || mappedFileQueue.getMappedFiles().size() != 1) {
            return count;
        }
        //the old logic will keep the last file, here to delete it
        MappedFile mappedFile = mappedFileQueue.getLastMappedFile();
        log.info("Try to delete the last old commitlog file {}", mappedFile.getFileName());
        long liveMaxTimestamp = mappedFile.getLastModifiedTimestamp() + expiredTime;
        if (System.currentTimeMillis() >= liveMaxTimestamp || cleanImmediately) {
            while (!mappedFile.destroy(10 * 1000)) {
                DLedgerUtils.sleep(1000);
            }
            mappedFileQueue.getMappedFiles().remove(mappedFile);
        }
        return 1;
    }

    /**
     * 将SelectMmapBufferResult转换为DLedgerSelectMappedBufferResult
     * @param sbr
     * @return
     */
    public SelectMappedBufferResult convertSbr(SelectMmapBufferResult sbr) {
        if (sbr == null) {
            return null;
        } else {
            return new DLedgerSelectMappedBufferResult(sbr);
        }

    }

    /**
     * 通过committedPos截取消息   即只返回经过raft算法后   得到commit的消息
     * @param sbr
     * @return
     */
    public SelectMmapBufferResult truncate(SelectMmapBufferResult sbr) {
        long committedPos = dLedgerFileStore.getCommittedPos();
        /**
         * sbr中只有没经过commit的消息
         */
        if (sbr == null || sbr.getStartOffset() == committedPos) {
            return null;
        }
        /**
         * sbr中的消息   都得到raft算法确认
         */
        if (sbr.getStartOffset() + sbr.getSize() <= committedPos) {
            return sbr;
        } else {
            /**
             * 只返回commit的消息   没有得到确认的消息不返回
             */
            sbr.setSize((int) (committedPos - sbr.getStartOffset()));
            return sbr;
        }
    }

    /**
     * 获取offset处为起始位置的所有消息
     *
     * @param offset
     * @return
     */
    @Override
    public SelectMappedBufferResult getData(final long offset) {
        if (offset < dividedCommitlogOffset) {
            /**
             * commitlog模式
             */
            return super.getData(offset);
        }
        /**
         * dledgercommitlog模式
         */
        return this.getData(offset, offset == 0);
    }

    /**
     * 获取offset处为起始位置的所有消息
     *
     * @param offset
     * @param returnFirstOnNotFound
     * @return
     */
    @Override
    public SelectMappedBufferResult getData(final long offset, final boolean returnFirstOnNotFound) {
        if (offset < dividedCommitlogOffset) {
            /**
             * commitlog模式
             */
            return super.getData(offset, returnFirstOnNotFound);
        }
        if (offset >= dLedgerFileStore.getCommittedPos()) {
            return null;
        }
        /**
         * 默认1g
         */
        int mappedFileSize = this.dLedgerServer.getdLedgerConfig().getMappedFileSizeForEntryData();
        /**
         * 获取offset对应的dledger文件
         */
        MmapFile mappedFile = this.dLedgerFileList.findMappedFileByOffset(offset, returnFirstOnNotFound);
        if (mappedFile != null) {
            int pos = (int) (offset % mappedFileSize);
            /**
             * 获取mappedFile文件   从pos位置开始的所有消息  是wrotePosition而不是committedPosition
             */
            SelectMmapBufferResult sbr = mappedFile.selectMappedBuffer(pos);
            /**
             * 获取sbr中   经过commit确认的消息
             */
            return convertSbr(truncate(sbr));
        }

        return null;
    }

    private void recover(long maxPhyOffsetOfConsumeQueue) {
        /**
         * 加载对应得data文件和index文件
         */
        dLedgerFileStore.load();
        if (dLedgerFileList.getMappedFiles().size() > 0) {
            /**
             * 执行dledger得recover
             */
            dLedgerFileStore.recover();
            /**
             * 区分普通存储和dledger存储
             */
            dividedCommitlogOffset = dLedgerFileList.getFirstMappedFile().getFileFromOffset();
            MappedFile mappedFile = this.mappedFileQueue.getLastMappedFile();
            if (mappedFile != null) {
                disableDeleteDledger();
            }
            /**
             * 获取commitlog存储得最大offset
             */
            long maxPhyOffset = dLedgerFileList.getMaxWrotePosition();
            // Clear ConsumeQueue redundant data
            /**
             * consumerqueue获得commitlog得最大offset  大于 commitlog获得得最大offset
             */
            if (maxPhyOffsetOfConsumeQueue >= maxPhyOffset) {
                log.warn("[TruncateCQ]maxPhyOffsetOfConsumeQueue({}) >= processOffset({}), truncate dirty logic files", maxPhyOffsetOfConsumeQueue, maxPhyOffset);
                //删除consumerqueue中得冗余文件
                this.defaultMessageStore.truncateDirtyLogicFiles(maxPhyOffset);
            }
            return;
        }
        //Indicate that, it is the first time to load mixed commitlog, need to recover the old commitlog
        //表示第一次加载混合提交日志，需要恢复旧的提交日志
        isInrecoveringOldCommitlog = true;
        //No need the abnormal recover
        /**
         * commitLog的recoverNormally
         */
        super.recoverNormally(maxPhyOffsetOfConsumeQueue);
        /**
         * 以dledger模式运行
         */
        isInrecoveringOldCommitlog = false;
        MappedFile mappedFile = this.mappedFileQueue.getLastMappedFile();
        if (mappedFile == null) {
            return;
        }
        ByteBuffer byteBuffer = mappedFile.sliceByteBuffer();
        byteBuffer.position(mappedFile.getWrotePosition());
        boolean needWriteMagicCode = true;
        // 1 TOTAL SIZE
        byteBuffer.getInt(); //size
        int magicCode = byteBuffer.getInt();
        if (magicCode == CommitLog.BLANK_MAGIC_CODE) {
            needWriteMagicCode = false;
        } else {
            log.info("Recover old commitlog found a illegal magic code={}", magicCode);
        }
        dLedgerConfig.setEnableDiskForceClean(false);
        dividedCommitlogOffset = mappedFile.getFileFromOffset() + mappedFile.getFileSize();
        log.info("Recover old commitlog needWriteMagicCode={} pos={} file={} dividedCommitlogOffset={}", needWriteMagicCode, mappedFile.getFileFromOffset() + mappedFile.getWrotePosition(), mappedFile.getFileName(), dividedCommitlogOffset);
        if (needWriteMagicCode) {
            byteBuffer.position(mappedFile.getWrotePosition());
            byteBuffer.putInt(mappedFile.getFileSize() - mappedFile.getWrotePosition());
            byteBuffer.putInt(BLANK_MAGIC_CODE);
            mappedFile.flush(0);
        }
        mappedFile.setWrotePosition(mappedFile.getFileSize());
        mappedFile.setCommittedPosition(mappedFile.getFileSize());
        mappedFile.setFlushedPosition(mappedFile.getFileSize());
        dLedgerFileList.getLastMappedFile(dividedCommitlogOffset);
        log.info("Will set the initial commitlog offset={} for dledger", dividedCommitlogOffset);
    }

    @Override
    public void recoverNormally(long maxPhyOffsetOfConsumeQueue) {
        recover(maxPhyOffsetOfConsumeQueue);
    }

    @Override
    public void recoverAbnormally(long maxPhyOffsetOfConsumeQueue) {
        recover(maxPhyOffsetOfConsumeQueue);
    }

    @Override
    public DispatchRequest checkMessageAndReturnSize(ByteBuffer byteBuffer, final boolean checkCRC) {
        return this.checkMessageAndReturnSize(byteBuffer, checkCRC, true);
    }

    @Override
    public DispatchRequest checkMessageAndReturnSize(ByteBuffer byteBuffer, final boolean checkCRC,
        final boolean readBody) {
        /**
         * 按非dledger方式  返回最近一条消息
         */
        if (isInrecoveringOldCommitlog) {
            return super.checkMessageAndReturnSize(byteBuffer, checkCRC, readBody);
        }
        try {
            int bodyOffset = DLedgerEntry.BODY_OFFSET;
            int pos = byteBuffer.position();
            int magic = byteBuffer.getInt();
            //In dledger, this field is size, it must be gt 0, so it could prevent collision
            /**
             * magicOld为byteBuffer得第2个int范围
             * 如果是dledger  magicOld应该是消息的size   如果是非dledger   magicOld应该对应的magic属性（参考commitlog存储结构）
             */
            int magicOld = byteBuffer.getInt();
            /**
             * 如果magicOld存储的是CommitLog的magic   则按commitlog的存储结构解析消息
             */
            if (magicOld == CommitLog.BLANK_MAGIC_CODE || magicOld == CommitLog.MESSAGE_MAGIC_CODE) {
                byteBuffer.position(pos);
                return super.checkMessageAndReturnSize(byteBuffer, checkCRC, readBody);
            }

            /**
             * dledger模式   消息为空
             */
            if (magic == MmapFileList.BLANK_MAGIC_CODE) {
                return new DispatchRequest(0, true);
            }

            /**
             * 指定消息的存储位置（为了兼容dledger之前的版本，dledger存储的body位置的数据，实际上就是对应的commitlog的数据）
             */
            byteBuffer.position(pos + bodyOffset);
            /**
             * 按commitlog的方式解析数据
             */
            DispatchRequest dispatchRequest = super.checkMessageAndReturnSize(byteBuffer, checkCRC, readBody);

            /**
             * 因为是dledger   所以在这里需要加上dledger数据的长度才是真正的消息体
             */
            if (dispatchRequest.isSuccess()) {
                dispatchRequest.setBufferSize(dispatchRequest.getMsgSize() + bodyOffset);
            } else if (dispatchRequest.getMsgSize() > 0) {
                dispatchRequest.setBufferSize(dispatchRequest.getMsgSize() + bodyOffset);
            }
            return dispatchRequest;
        } catch (Throwable ignored) {
        }

        return new DispatchRequest(-1, false /* success */);
    }

    @Override
    public boolean resetOffset(long offset) {
        //currently, it seems resetOffset has no use
        return false;
    }

    @Override
    public long getBeginTimeInLock() {
        return beginTimeInDledgerLock;
    }

    /**
     * 消息存储
     * @param msg
     * @return
     */
    @Override
    public PutMessageResult putMessage(final MessageExtBrokerInner msg) {
        // Set the storage time
        msg.setStoreTimestamp(System.currentTimeMillis());
        // Set the message body BODY CRC (consider the most appropriate setting
        // on the client)
        msg.setBodyCRC(UtilAll.crc32(msg.getBody()));

        StoreStatsService storeStatsService = this.defaultMessageStore.getStoreStatsService();

        String topic = msg.getTopic();
        int queueId = msg.getQueueId();

        //should be consistent with the old version
        final int tranType = MessageSysFlag.getTransactionValue(msg.getSysFlag());
        /**
         * 普通事务消息或事务提交
         */
        if (tranType == MessageSysFlag.TRANSACTION_NOT_TYPE
            || tranType == MessageSysFlag.TRANSACTION_COMMIT_TYPE) {
            // Delay Delivery
            /**
             * 延迟投递   重试的消息需要先进入延迟队列   等待延迟时间到达后   再进入重试队列
             */
            if (msg.getDelayTimeLevel() > 0) {
                if (msg.getDelayTimeLevel() > this.defaultMessageStore.getScheduleMessageService().getMaxDelayLevel()) {
                    msg.setDelayTimeLevel(this.defaultMessageStore.getScheduleMessageService().getMaxDelayLevel());
                }

                /**
                 * 发送到延迟队列  SCHEDULE_TOPIC_XXXX
                 */
                topic = TopicValidator.RMQ_SYS_SCHEDULE_TOPIC;
                /**
                 * 对应的延迟队列的queueid  delayLevel-1
                 */
                queueId = ScheduleMessageService.delayLevel2QueueId(msg.getDelayTimeLevel());

                // Backup real topic, queueId
                /**
                 * 在属性中存储真正的topic和queueuid
                 */
                MessageAccessor.putProperty(msg, MessageConst.PROPERTY_REAL_TOPIC, msg.getTopic());
                MessageAccessor.putProperty(msg, MessageConst.PROPERTY_REAL_QUEUE_ID, String.valueOf(msg.getQueueId()));
                msg.setPropertiesString(MessageDecoder.messageProperties2String(msg.getProperties()));

                /**
                 * 更换topic和queueuid
                 */
                msg.setTopic(topic);
                msg.setQueueId(queueId);
            }
        }

        // Back to Results
        AppendMessageResult appendResult;
        AppendFuture<AppendEntryResponse> dledgerFuture;
        EncodeResult encodeResult;

        putMessageLock.lock(); //spin or ReentrantLock ,depending on store config
        long elapsedTimeInLock;
        long queueOffset;
        try {
            beginTimeInDledgerLock = this.defaultMessageStore.getSystemClock().now();
            /**
             * 序列化msg
             */
            encodeResult = this.messageSerializer.serialize(msg);

            /**
             * 获取queueOffset
             */
            queueOffset = topicQueueTable.get(encodeResult.queueOffsetKey);
            if (encodeResult.status != AppendMessageStatus.PUT_OK) {
                return new PutMessageResult(PutMessageStatus.MESSAGE_ILLEGAL, new AppendMessageResult(encodeResult.status));
            }
            AppendEntryRequest request = new AppendEntryRequest();
            request.setGroup(dLedgerConfig.getGroup());
            /**
             * 只有master才会接受producer发送的消息   所以这里指定remoteId为自己
             */
            request.setRemoteId(dLedgerServer.getMemberState().getSelfId());
            request.setBody(encodeResult.data);

            /**
             * dledger   append消息   会在dledger代码中   调用appendHooks   回填commitlog的phyoffset
             * dledger   append消息   会在dledger代码中   调用appendHooks   回填commitlog的phyoffset
             * dledger   append消息   会在dledger代码中   调用appendHooks   回填commitlog的phyoffset
             * dledger   append消息   会在dledger代码中   调用appendHooks   回填commitlog的phyoffset
             * dledger   append消息   会在dledger代码中   调用appendHooks   回填commitlog的phyoffset
             */
            dledgerFuture = (AppendFuture<AppendEntryResponse>) dLedgerServer.handleAppend(request);
            if (dledgerFuture.getPos() == -1) {
                return new PutMessageResult(PutMessageStatus.OS_PAGECACHE_BUSY, new AppendMessageResult(AppendMessageStatus.UNKNOWN_ERROR));
            }
            /**
             * dledger模式下   commitlog数据结构   对应的phyoffset
             *
             * dledgerFuture.getPos()  为dledgercommitlog存储的位置
             * dledgerFuture.getPos()+DLedgerEntry.BODY_OFFSET  为commitlog消息体存储的位置
             */
            long wroteOffset = dledgerFuture.getPos() + DLedgerEntry.BODY_OFFSET;

            int msgIdLength = (msg.getSysFlag() & MessageSysFlag.STOREHOSTADDRESS_V6_FLAG) == 0 ? 4 + 4 + 8 : 16 + 4 + 8;
            ByteBuffer buffer = ByteBuffer.allocate(msgIdLength);

            /**
             * 计算messageId
             */
            String msgId = MessageDecoder.createMessageId(buffer, msg.getStoreHostBytes(), wroteOffset);
            elapsedTimeInLock = this.defaultMessageStore.getSystemClock().now() - beginTimeInDledgerLock;
            /**
             * 响应对象
             */
            appendResult = new AppendMessageResult(AppendMessageStatus.PUT_OK, wroteOffset, encodeResult.data.length, msgId, System.currentTimeMillis(), queueOffset, elapsedTimeInLock);
            switch (tranType) {
                case MessageSysFlag.TRANSACTION_PREPARED_TYPE:
                case MessageSysFlag.TRANSACTION_ROLLBACK_TYPE:
                    break;
                case MessageSysFlag.TRANSACTION_NOT_TYPE:
                case MessageSysFlag.TRANSACTION_COMMIT_TYPE:
                    // The next update ConsumeQueue information
                    /**
                     * queueOffset  +1
                     */
                    DLedgerCommitLog.this.topicQueueTable.put(encodeResult.queueOffsetKey, queueOffset + 1);
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            log.error("Put message error", e);
            return new PutMessageResult(PutMessageStatus.UNKNOWN_ERROR, new AppendMessageResult(AppendMessageStatus.UNKNOWN_ERROR));
        } finally {
            beginTimeInDledgerLock = 0;
            putMessageLock.unlock();
        }

        if (elapsedTimeInLock > 500) {
            log.warn("[NOTIFYME]putMessage in lock cost time(ms)={}, bodyLength={} AppendMessageResult={}", elapsedTimeInLock, msg.getBody().length, appendResult);
        }

        PutMessageStatus putMessageStatus = PutMessageStatus.UNKNOWN_ERROR;
        try {
            /**
             * append操作结果
             */
            AppendEntryResponse appendEntryResponse = dledgerFuture.get(3, TimeUnit.SECONDS);
            switch (DLedgerResponseCode.valueOf(appendEntryResponse.getCode())) {
                case SUCCESS:
                    putMessageStatus = PutMessageStatus.PUT_OK;
                    break;
                case INCONSISTENT_LEADER:
                case NOT_LEADER:
                case LEADER_NOT_READY:
                case DISK_FULL:
                    putMessageStatus = PutMessageStatus.SERVICE_NOT_AVAILABLE;
                    break;
                case WAIT_QUORUM_ACK_TIMEOUT:
                    //Do not return flush_slave_timeout to the client, for the ons client will ignore it.
                    putMessageStatus = PutMessageStatus.OS_PAGECACHE_BUSY;
                    break;
                case LEADER_PENDING_FULL:
                    putMessageStatus = PutMessageStatus.OS_PAGECACHE_BUSY;
                    break;
            }
        } catch (Throwable t) {
            log.error("Failed to get dledger append result", t);
        }

        PutMessageResult putMessageResult = new PutMessageResult(putMessageStatus, appendResult);
        if (putMessageStatus == PutMessageStatus.PUT_OK) {
            // Statistics
            storeStatsService.getSinglePutMessageTopicTimesTotal(msg.getTopic()).incrementAndGet();
            storeStatsService.getSinglePutMessageTopicSizeTotal(topic).addAndGet(appendResult.getWroteBytes());
        }
        return putMessageResult;
    }

    @Override
    public PutMessageResult putMessages(final MessageExtBatch messageExtBatch) {
        return new PutMessageResult(PutMessageStatus.MESSAGE_ILLEGAL, null);
    }


    /**
     * 消息存储
     * @param msg
     * @return
     */
    @Override
    public CompletableFuture<PutMessageResult> asyncPutMessage(MessageExtBrokerInner msg) {
        return CompletableFuture.completedFuture(this.putMessage(msg));
    }

    @Override
    public CompletableFuture<PutMessageResult> asyncPutMessages(MessageExtBatch messageExtBatch) {
        return CompletableFuture.completedFuture(putMessages(messageExtBatch));
    }

    @Override
    public SelectMappedBufferResult getMessage(final long offset, final int size) {
        /**
         * 按commitlog数据结构  获取消息
         */
        if (offset < dividedCommitlogOffset) {
            return super.getMessage(offset, size);
        }

        /**
         * dledger数据结构   获取消息
         */
        int mappedFileSize = this.dLedgerServer.getdLedgerConfig().getMappedFileSizeForEntryData();
        /**
         * 获取offset所在的MappedFile
         */
        MmapFile mappedFile = this.dLedgerFileList.findMappedFileByOffset(offset, offset == 0);
        if (mappedFile != null) {
            int pos = (int) (offset % mappedFileSize);
            /**
             * 截取mappedFile  从pos开始  长度为size的数据
             */
            return convertSbr(mappedFile.selectMappedBuffer(pos, size));
        }
        return null;
    }

    /**
     * 计算下个文件的初始offset
     * @param offset
     * @return
     */
    @Override
    public long rollNextFile(final long offset) {
        /**
         * 文件大小  默认1g
         */
        int mappedFileSize = this.defaultMessageStore.getMessageStoreConfig().getMappedFileSizeCommitLog();
        return offset + mappedFileSize - offset % mappedFileSize;
    }

    @Override
    public HashMap<String, Long> getTopicQueueTable() {
        return topicQueueTable;
    }

    @Override
    public void setTopicQueueTable(HashMap<String, Long> topicQueueTable) {
        this.topicQueueTable = topicQueueTable;
    }

    @Override
    public void destroy() {
        super.destroy();
        dLedgerFileList.destroy();
    }

    @Override
    public boolean appendData(long startOffset, byte[] data) {
        //the old ha service will invoke method, here to prevent it
        return false;
    }

    @Override
    public void checkSelf() {
        dLedgerFileList.checkSelf();
    }

    @Override
    public long lockTimeMills() {
        long diff = 0;
        long begin = this.beginTimeInDledgerLock;
        if (begin > 0) {
            diff = this.defaultMessageStore.now() - begin;
        }

        if (diff < 0) {
            diff = 0;
        }

        return diff;
    }

    class EncodeResult {
        private String queueOffsetKey;
        private byte[] data;
        private AppendMessageStatus status;

        public EncodeResult(AppendMessageStatus status, byte[] data, String queueOffsetKey) {
            this.data = data;
            this.status = status;
            this.queueOffsetKey = queueOffsetKey;
        }
    }

    class MessageSerializer {
        // File at the end of the minimum fixed length empty
        private static final int END_FILE_MIN_BLANK_LENGTH = 4 + 4;
        private final ByteBuffer msgIdMemory;
        private final ByteBuffer msgIdV6Memory;
        // Store the message content
        private final ByteBuffer msgStoreItemMemory;
        // The maximum length of the message
        private final int maxMessageSize;
        // Build Message Key
        private final StringBuilder keyBuilder = new StringBuilder();

        private final StringBuilder msgIdBuilder = new StringBuilder();

//        private final ByteBuffer hostHolder = ByteBuffer.allocate(8);

        MessageSerializer(final int size) {
            this.msgIdMemory = ByteBuffer.allocate(4 + 4 + 8);
            this.msgIdV6Memory = ByteBuffer.allocate(16 + 4 + 8);
            this.msgStoreItemMemory = ByteBuffer.allocate(size + END_FILE_MIN_BLANK_LENGTH);
            this.maxMessageSize = size;
        }

        public ByteBuffer getMsgStoreItemMemory() {
            return msgStoreItemMemory;
        }

        /**
         * 序列化
         * @param msgInner
         * @return
         */
        public EncodeResult serialize(final MessageExtBrokerInner msgInner) {
            // STORETIMESTAMP + STOREHOSTADDRESS + OFFSET <br>

            // PHY OFFSET
            /**
             * 暂时为0   dledger存储时  通过AppendHook来回填真正的物理偏移  phyoffset
             */
            long wroteOffset = 0;

            int sysflag = msgInner.getSysFlag();

            int bornHostLength = (sysflag & MessageSysFlag.BORNHOST_V6_FLAG) == 0 ? 4 + 4 : 16 + 4;
            int storeHostLength = (sysflag & MessageSysFlag.STOREHOSTADDRESS_V6_FLAG) == 0 ? 4 + 4 : 16 + 4;
            ByteBuffer bornHostHolder = ByteBuffer.allocate(bornHostLength);
            ByteBuffer storeHostHolder = ByteBuffer.allocate(storeHostLength);

            // Record ConsumeQueue information
            keyBuilder.setLength(0);
            keyBuilder.append(msgInner.getTopic());
            keyBuilder.append('-');
            keyBuilder.append(msgInner.getQueueId());
            String key = keyBuilder.toString();

            /**
             * 计算queueOffset  为空则初始化
             */
            Long queueOffset = DLedgerCommitLog.this.topicQueueTable.get(key);
            if (null == queueOffset) {
                queueOffset = 0L;
                DLedgerCommitLog.this.topicQueueTable.put(key, queueOffset);
            }

            /**
             * 是否为事务消息
             */
            // Transaction messages that require special handling
            final int tranType = MessageSysFlag.getTransactionValue(msgInner.getSysFlag());
            switch (tranType) {
                // Prepared and Rollback message is not consumed, will not enter the
                // consumer queuec
                case MessageSysFlag.TRANSACTION_PREPARED_TYPE:
                case MessageSysFlag.TRANSACTION_ROLLBACK_TYPE:
                    queueOffset = 0L;
                    break;
                case MessageSysFlag.TRANSACTION_NOT_TYPE:
                case MessageSysFlag.TRANSACTION_COMMIT_TYPE:
                default:
                    break;
            }

            /**
             * Serialize message
             * 计算msg中Properties
             */
            final byte[] propertiesData =
                msgInner.getPropertiesString() == null ? null : msgInner.getPropertiesString().getBytes(MessageDecoder.CHARSET_UTF8);

            final int propertiesLength = propertiesData == null ? 0 : propertiesData.length;

            if (propertiesLength > Short.MAX_VALUE) {
                log.warn("putMessage message properties length too long. length={}", propertiesData.length);
                return new EncodeResult(AppendMessageStatus.PROPERTIES_SIZE_EXCEEDED, null, key);
            }

            /**
             * 计算topic长度
             */
            final byte[] topicData = msgInner.getTopic().getBytes(MessageDecoder.CHARSET_UTF8);
            final int topicLength = topicData.length;

            /**
             * 计算body长度
             */
            final int bodyLength = msgInner.getBody() == null ? 0 : msgInner.getBody().length;

            /**
             * 计算即将存入commitlog的消息大小
             */
            final int msgLen = calMsgLength(msgInner.getSysFlag(), bodyLength, topicLength, propertiesLength);

            // Exceeds the maximum message
            /**
             * 消息大小不能大于4m
             */
            if (msgLen > this.maxMessageSize) {
                DLedgerCommitLog.log.warn("message size exceeded, msg total size: " + msgLen + ", msg body size: " + bodyLength
                    + ", maxMessageSize: " + this.maxMessageSize);
                return new EncodeResult(AppendMessageStatus.MESSAGE_SIZE_EXCEEDED, null, key);
            }


            // Initialization of storage space
            /**
             * 重置msgStoreItemMemory
             */
            this.resetByteBuffer(msgStoreItemMemory, msgLen);

            /**
             * 写入数据
             */
            // 1 TOTALSIZE
            this.msgStoreItemMemory.putInt(msgLen);
            // 2 MAGICCODE
            this.msgStoreItemMemory.putInt(DLedgerCommitLog.MESSAGE_MAGIC_CODE);
            // 3 BODYCRC
            this.msgStoreItemMemory.putInt(msgInner.getBodyCRC());
            // 4 QUEUEID
            this.msgStoreItemMemory.putInt(msgInner.getQueueId());
            // 5 FLAG
            this.msgStoreItemMemory.putInt(msgInner.getFlag());
            // 6 QUEUEOFFSET
            this.msgStoreItemMemory.putLong(queueOffset);
            // 7 PHYSICALOFFSET
            this.msgStoreItemMemory.putLong(wroteOffset);
            // 8 SYSFLAG
            this.msgStoreItemMemory.putInt(msgInner.getSysFlag());
            // 9 BORNTIMESTAMP
            this.msgStoreItemMemory.putLong(msgInner.getBornTimestamp());
            // 10 BORNHOST
            this.resetByteBuffer(bornHostHolder, bornHostLength);
            this.msgStoreItemMemory.put(msgInner.getBornHostBytes(bornHostHolder));
            // 11 STORETIMESTAMP
            this.msgStoreItemMemory.putLong(msgInner.getStoreTimestamp());
            // 12 STOREHOSTADDRESS
            this.resetByteBuffer(storeHostHolder, storeHostLength);
            this.msgStoreItemMemory.put(msgInner.getStoreHostBytes(storeHostHolder));
            //this.msgBatchMemory.put(msgInner.getStoreHostBytes());
            // 13 RECONSUMETIMES
            this.msgStoreItemMemory.putInt(msgInner.getReconsumeTimes());
            // 14 Prepared Transaction Offset
            this.msgStoreItemMemory.putLong(msgInner.getPreparedTransactionOffset());
            // 15 BODY
            this.msgStoreItemMemory.putInt(bodyLength);
            if (bodyLength > 0) {
                this.msgStoreItemMemory.put(msgInner.getBody());
            }
            // 16 TOPIC
            this.msgStoreItemMemory.put((byte) topicLength);
            this.msgStoreItemMemory.put(topicData);
            // 17 PROPERTIES
            this.msgStoreItemMemory.putShort((short) propertiesLength);
            if (propertiesLength > 0) {
                this.msgStoreItemMemory.put(propertiesData);
            }
            byte[] data = new byte[msgLen];
            this.msgStoreItemMemory.clear();

            /**
             * 将msgStoreItemMemory的内容写入data
             */
            this.msgStoreItemMemory.get(data);
            return new EncodeResult(AppendMessageStatus.PUT_OK, data, key);
        }

        private void resetByteBuffer(final ByteBuffer byteBuffer, final int limit) {
            byteBuffer.flip();
            byteBuffer.limit(limit);
        }

    }

    public static class DLedgerSelectMappedBufferResult extends SelectMappedBufferResult {

        private SelectMmapBufferResult sbr;

        public DLedgerSelectMappedBufferResult(SelectMmapBufferResult sbr) {
            super(sbr.getStartOffset(), sbr.getByteBuffer(), sbr.getSize(), null);
            this.sbr = sbr;
        }

        public synchronized void release() {
            super.release();
            if (sbr != null) {
                sbr.release();
            }
        }

    }

    public DLedgerServer getdLedgerServer() {
        return dLedgerServer;
    }

    public int getId() {
        return id;
    }

    public long getDividedCommitlogOffset() {
        return dividedCommitlogOffset;
    }
}
