package com.iantapply.orchestra.api;

/**
 * Platform-neutral operational snapshot.
 *
 * @param definitionCount registered definition count
 * @param activeExecutionCount active or recoverable execution count
 * @param activeWorkerCount workers currently processing executions
 * @param queuedTaskCount executions waiting for a worker
 */
public record OrchestraStatus(
        int definitionCount, int activeExecutionCount, int activeWorkerCount, int queuedTaskCount) {
    /**
     * Returns one consistent human-readable status line for commands and logs.
     *
     * @return formatted status summary
     */
    public String summary() {
        return "definitions=%d, active=%d, workers=%d, queued=%d"
                .formatted(definitionCount, activeExecutionCount, activeWorkerCount, queuedTaskCount);
    }
}
