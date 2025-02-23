/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.io.cdap;

import static org.apache.beam.sdk.io.cdap.MappingUtils.getOffsetFnForPluginClass;
import static org.apache.beam.sdk.io.cdap.MappingUtils.getPluginByClass;
import static org.apache.beam.sdk.io.cdap.MappingUtils.getReceiverBuilderByPluginClass;
import static org.apache.beam.sdk.util.Preconditions.checkStateNotNull;
import static org.apache.beam.vendor.guava.v26_0_jre.com.google.common.base.Preconditions.checkArgument;

import com.google.auto.value.AutoValue;
import io.cdap.cdap.api.plugin.PluginConfig;
import java.util.Map;
import org.apache.beam.sdk.annotations.Experimental;
import org.apache.beam.sdk.annotations.Experimental.Kind;
import org.apache.beam.sdk.coders.CannotProvideCoderException;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.io.hadoop.format.HDFSSynchronization;
import org.apache.beam.sdk.io.hadoop.format.HadoopFormatIO;
import org.apache.beam.sdk.io.sparkreceiver.SparkReceiverIO;
import org.apache.beam.sdk.transforms.MapElements;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.SerializableFunction;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PBegin;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PDone;
import org.apache.beam.sdk.values.TypeDescriptor;
import org.apache.commons.lang3.NotImplementedException;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapreduce.InputFormat;
import org.apache.hadoop.mapreduce.OutputFormat;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A {@link CdapIO} is a Transform for reading data from source or writing data to sink of a Cdap
 * Plugin. It uses {@link HadoopFormatIO} for Batch and SparkReceiverIO for Streaming.
 *
 * <h2>Read from Cdap Plugin Bounded Source</h2>
 *
 * <p>To configure {@link CdapIO} source, you must specify Cdap {@link Plugin}, Cdap {@link
 * PluginConfig}, key and value classes.
 *
 * <p>{@link Plugin} is the Wrapper class for the Cdap Plugin. It contains main information about
 * the Plugin. The object of the {@link Plugin} class can be created with the {@link
 * Plugin#createBatch(Class, Class, Class)} method. Method requires the following parameters:
 *
 * <ul>
 *   <li>{@link io.cdap.cdap.etl.api.batch.BatchSource} class
 *   <li>{@link InputFormat} class
 *   <li>{@link io.cdap.cdap.api.data.batch.InputFormatProvider} class
 * </ul>
 *
 * <p>For more information about the InputFormat and InputFormatProvider, see {@link
 * HadoopFormatIO}.
 *
 * <p>Every Cdap Plugin has its {@link PluginConfig} class with necessary fields to configure the
 * Plugin. You can set the {@link Map} of your parameters with the {@link
 * ConfigWrapper#withParams(Map)} method where the key is the field name.
 *
 * <p>For example, to create a basic {@link CdapIO#read()} transform:
 *
 * <pre>{@code
 * Pipeline p = ...; // Create pipeline.
 *
 * // Create PluginConfig for specific plugin
 * EmployeeConfig pluginConfig =
 *         new ConfigWrapper<>(EmployeeConfig.class).withParams(TEST_EMPLOYEE_PARAMS_MAP).build();
 *
 * // Read using CDAP batch plugin
 * p.apply("ReadBatch",
 * CdapIO.<String, String>read()
 *             .withCdapPlugin(
 *                 Plugin.createBatch(
 *                     EmployeeBatchSource.class,
 *                     EmployeeInputFormat.class,
 *                     EmployeeInputFormatProvider.class))
 *             .withPluginConfig(pluginConfig)
 *             .withKeyClass(String.class)
 *             .withValueClass(String.class));
 * }</pre>
 *
 * <h2>Write to Cdap Plugin Bounded Sink</h2>
 *
 * <p>To configure {@link CdapIO} sink, just as {@link CdapIO#read()} Cdap {@link Plugin}, Cdap
 * {@link PluginConfig}, key, value classes must be specified. In addition, it's necessary to
 * determine locks directory path {@link CdapIO.Write#withLocksDirPath(String)}. It's used for
 * {@link HDFSSynchronization} configuration for {@link HadoopFormatIO}. More info can be found in
 * {@link HadoopFormatIO} documentation.
 *
 * <p>To create the object of the {@link Plugin} class with the {@link Plugin#createBatch(Class,
 * Class, Class)} method, need to specify the following parameters:
 *
 * <ul>
 *   <li>{@link io.cdap.cdap.etl.api.batch.BatchSink} class
 *   <li>{@link OutputFormat} class
 *   <li>{@link io.cdap.cdap.api.data.batch.OutputFormatProvider} class
 * </ul>
 *
 * <p>For more information about the OutputFormat and OutputFormatProvider, see {@link
 * HadoopFormatIO}.
 *
 * <p>Example of {@link CdapIO#write()} usage:
 *
 * <pre>{@code
 * Pipeline p = ...; // Create pipeline.
 *
 * // Get or create data to write
 * PCollection<KV<String, String>> input = p.apply(Create.of(data));
 *
 * // Create PluginConfig for specific plugin
 * EmployeeConfig pluginConfig =
 *         new ConfigWrapper<>(EmployeeConfig.class).withParams(TEST_EMPLOYEE_PARAMS_MAP).build();
 *
 * // Write using CDAP batch plugin
 * input.apply(
 *         "WriteBatch",
 *         CdapIO.<String, String>write()
 *             .withCdapPlugin(
 *                 Plugin.createBatch(
 *                     EmployeeBatchSink.class,
 *                     EmployeeOutputFormat.class,
 *                     EmployeeOutputFormatProvider.class))
 *             .withPluginConfig(pluginConfig)
 *             .withKeyClass(String.class)
 *             .withValueClass(String.class)
 *             .withLocksDirPath(tmpFolder.getRoot().getAbsolutePath()));
 *     p.run();
 * }</pre>
 *
 * <h2>Read from Cdap Plugin Streaming Source</h2>
 *
 * <p>To configure {@link CdapIO} source, you must specify Cdap {@link Plugin}, Cdap {@link
 * PluginConfig}, key and value classes.
 *
 * <p>{@link Plugin} is the Wrapper class for the Cdap Plugin. It contains main information about
 * the Plugin. The object of the {@link Plugin} class can be created with the {@link
 * Plugin#createStreaming(Class)} method. Method requires {@link
 * io.cdap.cdap.etl.api.streaming.StreamingSource} class parameter.
 *
 * <p>Every Cdap Plugin has its {@link PluginConfig} class with necessary fields to configure the
 * Plugin. You can set the {@link Map} of your parameters with the {@link
 * ConfigWrapper#withParams(Map)} method where the key is the field name.
 *
 * <p>For example, to create a basic {@link CdapIO#read()} transform:
 *
 * <pre>{@code
 * Pipeline p = ...; // Create pipeline.
 *
 * // Create PluginConfig for specific plugin
 * EmployeeConfig pluginConfig =
 *         new ConfigWrapper<>(EmployeeConfig.class).withParams(TEST_EMPLOYEE_PARAMS_MAP).build();
 *
 * // Read using CDAP streaming plugin
 * p.apply("ReadStreaming",
 * CdapIO.<String, String>read()
 *             .withCdapPlugin(Plugin.createStreaming(EmployeeStreamingSource.class))
 *             .withPluginConfig(pluginConfig)
 *             .withKeyClass(String.class)
 *             .withValueClass(String.class));
 * }</pre>
 */
@Experimental(Kind.SOURCE_SINK)
public class CdapIO {

  public static <K, V> Read<K, V> read() {
    return new AutoValue_CdapIO_Read.Builder<K, V>().build();
  }

  public static <K, V> Write<K, V> write() {
    return new AutoValue_CdapIO_Write.Builder<K, V>().build();
  }

  /** A {@link PTransform} to read from CDAP source. */
  @AutoValue
  @AutoValue.CopyAnnotations
  public abstract static class Read<K, V> extends PTransform<PBegin, PCollection<KV<K, V>>> {

    abstract @Nullable PluginConfig getPluginConfig();

    abstract @Nullable Plugin getCdapPlugin();

    /**
     * Depending on selected {@link HadoopFormatIO} type ({@link InputFormat} or {@link
     * OutputFormat}), appropriate key class ("key.class") in Hadoop {@link Configuration} must be
     * provided. If you set different Format key class than Format's actual key class then, it may
     * result in an error. More info can be found in {@link HadoopFormatIO} documentation.
     */
    abstract @Nullable Class<K> getKeyClass();

    /**
     * Depending on selected {@link HadoopFormatIO} type ({@link InputFormat} or {@link
     * OutputFormat}), appropriate value class ("value.class") in Hadoop {@link Configuration} must
     * be provided. If you set different Format value class than Format's actual value class then,
     * it may result in an error. More info can be found in {@link HadoopFormatIO} documentation.
     */
    abstract @Nullable Class<V> getValueClass();

    abstract Builder<K, V> toBuilder();

    @Experimental(Experimental.Kind.PORTABILITY)
    @AutoValue.Builder
    abstract static class Builder<K, V> {

      abstract Builder<K, V> setPluginConfig(PluginConfig config);

      abstract Builder<K, V> setCdapPlugin(Plugin plugin);

      abstract Builder<K, V> setKeyClass(Class<K> keyClass);

      abstract Builder<K, V> setValueClass(Class<V> valueClass);

      abstract Read<K, V> build();
    }

    /** Sets a CDAP {@link Plugin}. */
    public Read<K, V> withCdapPlugin(Plugin plugin) {
      checkArgument(plugin != null, "Cdap plugin can not be null");
      return toBuilder().setCdapPlugin(plugin).build();
    }

    /** Sets a CDAP Plugin class. */
    public Read<K, V> withCdapPluginClass(Class<?> cdapPluginClass) {
      checkArgument(cdapPluginClass != null, "Cdap plugin class can not be null");
      Plugin plugin = MappingUtils.getPluginByClass(cdapPluginClass);
      return toBuilder().setCdapPlugin(plugin).build();
    }

    /** Sets a {@link PluginConfig}. */
    public Read<K, V> withPluginConfig(PluginConfig pluginConfig) {
      checkArgument(pluginConfig != null, "Plugin config can not be null");
      return toBuilder().setPluginConfig(pluginConfig).build();
    }

    /** Sets a key class. */
    public Read<K, V> withKeyClass(Class<K> keyClass) {
      checkArgument(keyClass != null, "Key class can not be null");
      return toBuilder().setKeyClass(keyClass).build();
    }

    /** Sets a value class. */
    public Read<K, V> withValueClass(Class<V> valueClass) {
      checkArgument(valueClass != null, "Value class can not be null");
      return toBuilder().setValueClass(valueClass).build();
    }

    @Override
    public PCollection<KV<K, V>> expand(PBegin input) {
      Plugin cdapPlugin = getCdapPlugin();
      checkStateNotNull(cdapPlugin, "withCdapPluginClass() is required");

      PluginConfig pluginConfig = getPluginConfig();
      checkStateNotNull(pluginConfig, "withPluginConfig() is required");

      Class<V> valueClass = getValueClass();
      checkStateNotNull(valueClass, "withValueClass() is required");

      Class<K> keyClass = getKeyClass();
      checkStateNotNull(keyClass, "withKeyClass() is required");

      cdapPlugin.withConfig(pluginConfig);

      if (cdapPlugin.isUnbounded()) {
        SparkReceiverIO.Read<V> reader =
            SparkReceiverIO.<V>read()
                .withGetOffsetFn(getOffsetFnForPluginClass(cdapPlugin.getPluginClass(), valueClass))
                .withSparkReceiverBuilder(
                    getReceiverBuilderByPluginClass(
                        cdapPlugin.getPluginClass(), pluginConfig, valueClass));
        try {
          Coder<V> coder = input.getPipeline().getCoderRegistry().getCoder(valueClass);
          PCollection<V> values = input.apply(reader).setCoder(coder);
          SerializableFunction<V, KV<K, V>> fn = input1 -> KV.of(null, input1);
          return values.apply(MapElements.into(new TypeDescriptor<KV<K, V>>() {}).via(fn));
        } catch (CannotProvideCoderException e) {
          throw new IllegalStateException("Could not get value Coder", e);
        }
      } else {
        cdapPlugin.withHadoopConfiguration(keyClass, valueClass).prepareRun();
        Configuration hConf = cdapPlugin.getHadoopConfiguration();
        HadoopFormatIO.Read<K, V> readFromHadoop =
            HadoopFormatIO.<K, V>read().withConfiguration(hConf);
        return input.apply(readFromHadoop);
      }
    }
  }

  /** A {@link PTransform} to write to CDAP sink. */
  @AutoValue
  @AutoValue.CopyAnnotations
  public abstract static class Write<K, V> extends PTransform<PCollection<KV<K, V>>, PDone> {

    abstract @Nullable PluginConfig getPluginConfig();

    abstract @Nullable Plugin getCdapPlugin();

    /**
     * Depending on selected {@link HadoopFormatIO} type ({@link InputFormat} or {@link
     * OutputFormat}), appropriate key class ("key.class") in Hadoop {@link Configuration} must be
     * provided. If you set different Format key class than Format's actual key class then, it may
     * result in an error. More info can be found in {@link HadoopFormatIO} documentation.
     */
    abstract @Nullable Class<K> getKeyClass();

    /**
     * Depending on selected {@link HadoopFormatIO} type ({@link InputFormat} or {@link
     * OutputFormat}), appropriate value class ("value.class") in Hadoop {@link Configuration} must
     * be provided. If you set different Format value class than Format's actual value class then,
     * it may result in an error. More info can be found in {@link HadoopFormatIO} documentation.
     */
    abstract @Nullable Class<V> getValueClass();

    /**
     * Directory where locks will be stored. This directory MUST be different that directory which
     * is possibly stored under FileOutputFormat.outputDir key. Used for {@link HDFSSynchronization}
     * configuration for {@link HadoopFormatIO}. More info can be found in {@link HadoopFormatIO}
     * documentation.
     */
    abstract @Nullable String getLocksDirPath();

    abstract Builder<K, V> toBuilder();

    @Experimental(Experimental.Kind.PORTABILITY)
    @AutoValue.Builder
    abstract static class Builder<K, V> {

      abstract Builder<K, V> setPluginConfig(PluginConfig config);

      abstract Builder<K, V> setCdapPlugin(Plugin plugin);

      abstract Builder<K, V> setKeyClass(Class<K> keyClass);

      abstract Builder<K, V> setValueClass(Class<V> valueClass);

      abstract Builder<K, V> setLocksDirPath(String path);

      abstract Write<K, V> build();
    }

    /** Sets a CDAP {@link Plugin}. */
    public Write<K, V> withCdapPlugin(Plugin plugin) {
      checkArgument(plugin != null, "Cdap plugin can not be null");
      return toBuilder().setCdapPlugin(plugin).build();
    }

    /** Sets a CDAP Plugin class. */
    public Write<K, V> withCdapPluginClass(Class<?> cdapPluginClass) {
      checkArgument(cdapPluginClass != null, "Cdap plugin class can not be null");
      Plugin plugin = getPluginByClass(cdapPluginClass);
      return toBuilder().setCdapPlugin(plugin).build();
    }

    /** Sets a {@link PluginConfig}. */
    public Write<K, V> withPluginConfig(PluginConfig pluginConfig) {
      checkArgument(pluginConfig != null, "Plugin config can not be null");
      return toBuilder().setPluginConfig(pluginConfig).build();
    }

    /** Sets a key class. */
    public Write<K, V> withKeyClass(Class<K> keyClass) {
      checkArgument(keyClass != null, "Key class can not be null");
      return toBuilder().setKeyClass(keyClass).build();
    }

    /** Sets path to directory where locks will be stored. */
    public Write<K, V> withLocksDirPath(String locksDirPath) {
      checkArgument(locksDirPath != null, "Locks dir path can not be null");
      return toBuilder().setLocksDirPath(locksDirPath).build();
    }

    /** Sets a value class. */
    public Write<K, V> withValueClass(Class<V> valueClass) {
      checkArgument(valueClass != null, "Value class can not be null");
      return toBuilder().setValueClass(valueClass).build();
    }

    @Override
    public PDone expand(PCollection<KV<K, V>> input) {
      Plugin cdapPlugin = getCdapPlugin();
      checkStateNotNull(cdapPlugin, "withCdapPluginClass() is required");

      PluginConfig pluginConfig = getPluginConfig();
      checkStateNotNull(pluginConfig, "withPluginConfig() is required");

      Class<K> keyClass = getKeyClass();
      checkStateNotNull(keyClass, "withKeyClass() is required");
      Class<V> valueClass = getValueClass();
      checkStateNotNull(valueClass, "withValueClass() is required");

      String locksDirPath = getLocksDirPath();
      checkStateNotNull(locksDirPath, "withLocksDirPath() is required");

      cdapPlugin
          .withConfig(pluginConfig)
          .withHadoopConfiguration(keyClass, valueClass)
          .prepareRun();

      if (cdapPlugin.isUnbounded()) {
        // TODO: implement SparkReceiverIO.<~>write()
        throw new NotImplementedException("Support for unbounded plugins is not implemented!");
      } else {
        Configuration hConf = cdapPlugin.getHadoopConfiguration();
        HadoopFormatIO.Write<K, V> writeHadoop =
            HadoopFormatIO.<K, V>write()
                .withConfiguration(hConf)
                .withPartitioning()
                .withExternalSynchronization(new HDFSSynchronization(locksDirPath));
        return input.apply(writeHadoop);
      }
    }
  }
}
