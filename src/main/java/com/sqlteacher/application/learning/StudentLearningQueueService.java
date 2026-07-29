package com.sqlteacher.application.learning;

public interface StudentLearningQueueService {
    StudentLearningQueue refresh();

    void dismiss(StudentLearningQueueItem item);

    void complete(StudentLearningQueueItem item);
}
