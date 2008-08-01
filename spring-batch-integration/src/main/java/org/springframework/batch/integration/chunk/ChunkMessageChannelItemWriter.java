package org.springframework.batch.integration.chunk;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.listener.StepExecutionListenerSupport;
import org.springframework.batch.item.ClearFailedException;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.FlushFailedException;
import org.springframework.batch.item.ItemStream;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.repeat.ExitStatus;
import org.springframework.batch.repeat.RepeatContext;
import org.springframework.integration.message.BlockingSource;
import org.springframework.integration.message.GenericMessage;
import org.springframework.integration.message.Message;
import org.springframework.integration.message.MessageTarget;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.Assert;

public class ChunkMessageChannelItemWriter<T> extends StepExecutionListenerSupport implements ItemWriter<T>, ItemStream {

	private static final Log logger = LogFactory.getLog(ChunkMessageChannelItemWriter.class);

	/**
	 * Key for items processed in the current transaction {@link RepeatContext}.
	 */
	private static final String ITEMS_PROCESSED = ChunkMessageChannelItemWriter.class.getName() + ".ITEMS_PROCESSED";

	static final String ACTUAL = "ACTUAL";

	static final String EXPECTED = "EXPECTED";

	private static final long DEFAULT_THROTTLE_LIMIT = 6;

	private MessageTarget target;

	private BlockingSource<ChunkResponse> source;

	// TODO: abstract the state or make a factory for this writer?
	private LocalState localState = new LocalState();

	private long throttleLimit = DEFAULT_THROTTLE_LIMIT;

	/**
	 * Public setter for the throttle limit. This limits the number of pending
	 * requests for chunk processing to avoid overwhelming the receivers.
	 * @param throttleLimit the throttle limit to set
	 */
	public void setThrottleLimit(long throttleLimit) {
		this.throttleLimit = throttleLimit;
	}

	public void setSource(BlockingSource<ChunkResponse> source) {
		this.source = source;
	}

	public void setTarget(MessageTarget target) {
		this.target = target;
	}

	public void write(T item) throws Exception {
		bindTransactionResources();
		getProcessed().add(item);
		logger.debug("Added item to chunk: " + item);
	}

	/**
	 * Flush the buffer, sending the items as a chunk message to be processed by
	 * a {@link ChunkHandler}. To avoid overwhelming the receivers, this method
	 * will block until the number of chunks pending is less than the throttle
	 * limit.
	 * 
	 * @see org.springframework.batch.item.ItemWriter#flush()
	 */
	public void flush() throws FlushFailedException {

		bindTransactionResources(); // in case we are called outside a
		// transaction

		// Block until expecting <= throttle limit - can Spring
		// Integration do that for me?
		while (localState.getExpecting() > throttleLimit) {
			getNextResult(100);
		}

		List<T> processed = getProcessed();

		if (!processed.isEmpty()) {

			logger.debug("Dispatching chunk: " + processed);
			ChunkRequest<T> request = new ChunkRequest<T>(processed, localState.getJobId(), localState.getSkipCount());
			GenericMessage<ChunkRequest<T>> message = new GenericMessage<ChunkRequest<T>>(request);
			target.send(message);
			localState.expected++;

		}

		// Short little timeout to look for an immediate reply.
		getNextResult(1);

		unbindTransactionResources();

	}

	@Override
	public void beforeStep(StepExecution stepExecution) {
		localState.setStepExecution(stepExecution);
	}

	@Override
	public ExitStatus afterStep(StepExecution stepExecution) {
		if (!(stepExecution.getStatus() == BatchStatus.COMPLETED)) {
			return ExitStatus.CONTINUABLE;
		}
		long expecting = localState.getExpecting();
		boolean timedOut;
		try {
			logger.debug("Waiting for results in step listener...");
			timedOut = !waitForResults();
			logger.debug("Finished waiting for results in step listener.");
		}
		catch (RuntimeException e) {
			logger.debug("Detected failure waiting for results in step listener.");
			stepExecution.setStatus(BatchStatus.FAILED);
			return ExitStatus.FAILED.addExitDescription(e.getClass().getName() + ": " + e.getMessage());
		}
		if (timedOut) {
			stepExecution.setStatus(BatchStatus.FAILED);
			throw new ItemStreamException("Timed out waiting for back log at end of step");
		}
		return ExitStatus.FINISHED.addExitDescription("Waited for " + expecting + " results.");
	}

	public void close(ExecutionContext executionContext) throws ItemStreamException {
		localState.reset();
	}

	public void open(ExecutionContext executionContext) throws ItemStreamException {
		if (executionContext.containsKey(EXPECTED)) {
			localState.expected = executionContext.getLong(EXPECTED);
			localState.actual = executionContext.getLong(ACTUAL);
			if (!waitForResults()) {
				throw new ItemStreamException("Timed out waiting for back log on open");
			}
		}
	}

	public void update(ExecutionContext executionContext) throws ItemStreamException {
		executionContext.putLong(EXPECTED, localState.expected);
		executionContext.putLong(ACTUAL, localState.actual);
	}

	/**
	 * Wait until all the results that are in the pipeline come back to the
	 * reply channel.
	 * 
	 * @return true if successfully received a result, false if timed out
	 */
	private boolean waitForResults() {
		// TODO: cumulative timeout, or throw an exception?
		int count = 0;
		int maxCount = 40;
		while (localState.getExpecting() > 0 && count++ < maxCount) {
			getNextResult(100);
		}
		return count < maxCount;
	}

	/**
	 * Get the next result if it is available within the timeout specified,
	 * otherwise return null.
	 */
	private void getNextResult(long timeout) {
		// TODO: use the timeout
		Message<ChunkResponse> message = source.receive(timeout);
		if (message != null) {
			ChunkResponse payload = message.getPayload();
			Long jobInstanceId = payload.getJobId();
			Assert.state(jobInstanceId != null, "Message did not contain job instance id.");
			Assert.state(jobInstanceId.equals(localState.getJobId()), "Message contained wrong job instance id ["
					+ jobInstanceId + "] should have been [" + localState.getJobId() + "].");
			localState.actual++;
			ExitStatus result = payload.getExitStatus();
			// TODO: check it can never be ExitStatus.FINISHED?
			if (!result.isContinuable()) {
				throw new AsynchronousFailureException("Failure or early completion detected in handler: " + result);
			}
		}
	}

	/**
	 * Accessor for the list of processed items in this transaction.
	 * 
	 * @return the processed
	 */
	private List<T> getProcessed() {
		Assert.state(TransactionSynchronizationManager.hasResource(ITEMS_PROCESSED),
				"Processed items not bound to transaction.");
		@SuppressWarnings("unchecked")
		List<T> processed = (List<T>) TransactionSynchronizationManager.getResource(ITEMS_PROCESSED);
		return processed;
	}

	/**
	 * Set up the {@link RepeatContext} as a transaction resource.
	 * 
	 * @param context the context to set
	 */
	private void bindTransactionResources() {
		if (TransactionSynchronizationManager.hasResource(ITEMS_PROCESSED)) {
			return;
		}
		TransactionSynchronizationManager.bindResource(ITEMS_PROCESSED, new ArrayList<Object>());
	}

	/**
	 * Remove the transaction resource associated with this context.
	 */
	private void unbindTransactionResources() {
		if (!TransactionSynchronizationManager.hasResource(ITEMS_PROCESSED)) {
			return;
		}
		TransactionSynchronizationManager.unbindResource(ITEMS_PROCESSED);
	}

	/**
	 * Clear the buffer.
	 * 
	 * @see org.springframework.batch.item.ItemWriter#clear()
	 */
	public void clear() throws ClearFailedException {
		unbindTransactionResources();
	}

	private static class LocalState {
		private long actual;

		private long expected;

		private StepExecution stepExecution;

		public long getExpecting() {
			return expected - actual;
		}

		public int getSkipCount() {
			// TODO Auto-generated method stub
			return stepExecution.getSkipCount();
		}

		public Long getJobId() {
			return stepExecution.getJobExecution().getJobId();
		}

		public void setStepExecution(StepExecution stepExecution) {
			this.stepExecution = stepExecution;
		}

		public void reset() {
			expected = actual = 0;
		}
	}

}
