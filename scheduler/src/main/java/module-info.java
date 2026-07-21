/**
 * Ligero scheduler: a tiny, dependency-free scheduler for background tasks.
 * Timing runs on a small daemon pool; each task runs on its own virtual thread.
 */
module com.ligero.scheduler {
    requires org.slf4j;

    exports com.ligero.scheduler;
}
