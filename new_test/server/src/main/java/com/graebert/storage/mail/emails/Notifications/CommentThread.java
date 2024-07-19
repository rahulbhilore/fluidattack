package com.graebert.storage.mail.emails.Notifications;

import java.util.List;

public class CommentThread {
  ThreadMeta meta;
  List<Event> events;

  public CommentThread(List<Event> events, ThreadMeta meta) {
    this.events = events;
    this.meta = meta;
  }
}
