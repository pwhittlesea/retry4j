package com.evanlennick.retry4j;

import com.evanlennick.retry4j.exception.CallFailureException;
import com.evanlennick.retry4j.exception.UnexpectedCallFailureException;
import com.evanlennick.retry4j.handlers.Listener;
import com.evanlennick.retry4j.handlers.RetryListener;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

public class RetryExecutor {

    private RetryConfig config;

    private RetryListener retryListener;

    private RetryResults results = new RetryResults();

    public RetryExecutor() {
        this(RetryConfig.simpleFixedConfig());
    }

    public RetryExecutor(RetryConfig config) {
        this.config = config;
    }

    public RetryResults execute(Callable<Boolean> callable) throws CallFailureException, UnexpectedCallFailureException {
        long start = System.currentTimeMillis();
        results.setStartTime(start);

        int maxTries = config.getMaxNumberOfTries();
        long millisBetweenTries = config.getDelayBetweenRetries().toMillis();
        this.results.setCallName(callable.toString());

        boolean success = false;
        int tries;
        for (tries = 0; tries < maxTries && !success; tries++) {
            success = tryCall(callable);

            if (!success) {
                handleRetry(millisBetweenTries, tries);
            }
        }

        refreshRetryResults(success, tries);

        if (!success) {
            String failureMsg = String.format("Call '%s' failed after %d tries!", callable.toString(), maxTries);
            throw new CallFailureException(failureMsg, results);
        } else {
            return results;
        }
    }

    private boolean tryCall(Callable<Boolean> callable) throws UnexpectedCallFailureException {
        boolean success = false;
        try {
            success = callable.call();
        } catch (Exception e) {
            if (shouldThrowException(e)) {
                throw new UnexpectedCallFailureException(e);
            }
        }
        return success;
    }

    private void handleRetry(long millisBetweenTries, int tries) {
        refreshRetryResults(false, tries);

        if(null != retryListener) {
            retryListener.immediatelyAfterFailedTry(results);
        }

        sleep(millisBetweenTries, tries);

        if(null != retryListener) {
            retryListener.immediatelyBeforeNextTry(results);
        }
    }

    private void refreshRetryResults(boolean success, int tries) {
        long currentTime = System.currentTimeMillis();
        long elapsed = currentTime - results.getStartTime();

        results.setTotalTries(tries);
        results.setTotalElapsedDuration(Duration.of(elapsed, ChronoUnit.MILLIS));
        results.setSuccessful(success);
    }

    private void sleep(long millis, int tries) {
        Duration duration = Duration.of(millis, ChronoUnit.MILLIS);
        long millisToSleep = config.getBackoffStrategy().getMillisToWait(tries, duration);

        try {
            TimeUnit.MILLISECONDS.sleep(millisToSleep);
        } catch (InterruptedException ignored) {
        }
    }

    private boolean shouldThrowException(Exception e) {
        if (this.config.isRetryOnAnyException()) {
            return false;
        }

        for (Class<? extends Exception> exceptionInSet : this.config.getRetryOnSpecificExceptions()) {
            if (e.getClass().isAssignableFrom(exceptionInSet)) {
                return false;
            }
        }

        return true;
    }

    public void registerRetryListener(RetryListener listener) {
        this.retryListener = listener;
    }
}
