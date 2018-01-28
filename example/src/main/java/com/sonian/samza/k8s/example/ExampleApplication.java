/*
 * Copyright 2018 Barracuda, Inc.

 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at

 *   http://www.apache.org/licenses/LICENSE-2.0

 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.sonian.samza.k8s.example;

import java.time.Duration;

import org.apache.samza.application.StreamApplication;
import org.apache.samza.config.Config;
import org.apache.samza.operators.MessageStream;
import org.apache.samza.operators.OutputStream;
import org.apache.samza.operators.StreamGraph;
import org.apache.samza.operators.functions.FoldLeftFunction;
import org.apache.samza.operators.windows.Windows;
import org.apache.samza.runtime.LocalApplicationRunner;
import org.apache.samza.serializers.JsonSerdeV2;
import org.apache.samza.serializers.Serde;
import org.apache.samza.serializers.StringSerde;
import org.apache.samza.storage.kv.KeyValueStore;
import org.apache.samza.task.TaskContext;
import org.apache.samza.util.CommandLine;

import joptsimple.OptionSet;

/**
 * This example application accepts messages from `INPUT_STREAM_ID` in the form:
 *
 * <code>
 * {"thing": "a", "count": 1} {"thing": "b", "count": 3} {"thing": "a", "count": 7} ...
 * </code>
 *
 * Sums counts from messages with the same key over a period of 1 minute and sends them to
 * `OUTPUT_STREAM_ID`, e.g.:
 *
 * <code>
 * {"thing": "a", "count": 8} {"thing": "b", "count": 3} ...
 * </code>
 *
 * It also uses the local KV store to track how many times a particular "thing" is seen.
 */
public class ExampleApplication implements StreamApplication {

  private static final String INPUT_STREAM_ID = "example-input";
  private static final String OUTPUT_STREAM_ID = "example-output";
  private static final String COUNTS_STORE_NAME = "counts-store";

  @Override
  public void init(StreamGraph graph, Config config) {
    final Serde<Message> messageSerde = JsonSerdeV2.of(Message.class);
    graph.setDefaultSerde(messageSerde);

    final MessageStream<Message> input = graph.getInputStream(INPUT_STREAM_ID);
    final OutputStream<Message> output = graph.getOutputStream(OUTPUT_STREAM_ID);

    input
        .window(Windows.keyedTumblingWindow(Message::getThing, Duration.ofMinutes(1), Message::new,
            new CountAggregator(), new StringSerde(), messageSerde), "count-agg")
        .map(win -> win.getMessage()).sendTo(output);
  }

  public static void main(String[] args) {
    CommandLine cmdLine = new CommandLine();
    OptionSet options = cmdLine.parser().parse(args);
    Config config = cmdLine.loadConfig(options);

    LocalApplicationRunner runner = new LocalApplicationRunner(config);
    runner.run(new ExampleApplication());
    runner.waitForFinish();
  }

  private class Message {
    private String thing;
    private int count;

    public String getThing() {
      return thing;
    }

    public void setThing(String thing) {
      this.thing = thing;
    }

    public int getCount() {
      return count;
    }

    public void setCount(int count) {
      this.count = count;
    }

  }

  private class CountAggregator implements FoldLeftFunction<Message, Message> {

    private KeyValueStore<String, Integer> store;

    @SuppressWarnings("unchecked")
    @Override
    public void init(Config config, TaskContext context) {
      store = (KeyValueStore<String, Integer>) context.getStore(COUNTS_STORE_NAME);
    }

    @Override
    public Message apply(Message msg, Message oldVal) {
      Integer totalSeenThing = store.get(msg.getThing());
      if (totalSeenThing == null) {
        totalSeenThing = 0;
      }
      store.put(msg.getThing(), totalSeenThing + 1);

      final Message newVal = new Message();
      newVal.setThing(msg.getThing());
      newVal.setCount(oldVal.getCount() + msg.getCount());
      return newVal;
    }
  }

}
