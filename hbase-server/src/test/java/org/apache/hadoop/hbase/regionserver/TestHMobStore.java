/**
 *
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
package org.apache.hadoop.hbase.regionserver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.NavigableSet;
import java.util.concurrent.ConcurrentSkipListSet;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.MediumTests;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.mob.MobConstants;
import org.apache.hadoop.hbase.mob.MobUtils;
import org.apache.hadoop.hbase.monitoring.MonitoredTask;
import org.apache.hadoop.hbase.wal.WAL;
import org.apache.hadoop.hbase.wal.WALFactory;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.FSUtils;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.TestName;
import org.mockito.Mockito;

@Category(MediumTests.class)
public class TestHMobStore {
  public static final Log LOG = LogFactory.getLog(TestHMobStore.class);
  @Rule public TestName name = new TestName();

  private HMobStore store;
  private HRegion region;
  private HColumnDescriptor hcd;
  private FileSystem fs;
  private byte [] table = Bytes.toBytes("table");
  private byte [] family = Bytes.toBytes("family");
  private byte [] row = Bytes.toBytes("row");
  private byte [] row2 = Bytes.toBytes("row2");
  private byte [] qf1 = Bytes.toBytes("qf1");
  private byte [] qf2 = Bytes.toBytes("qf2");
  private byte [] qf3 = Bytes.toBytes("qf3");
  private byte [] qf4 = Bytes.toBytes("qf4");
  private byte [] qf5 = Bytes.toBytes("qf5");
  private byte [] qf6 = Bytes.toBytes("qf6");
  private byte[] value = Bytes.toBytes("value");
  private byte[] value2 = Bytes.toBytes("value2");
  private Path mobFilePath;
  private Date currentDate = new Date();
  private KeyValue seekKey1;
  private KeyValue seekKey2;
  private KeyValue seekKey3;
  private NavigableSet<byte[]> qualifiers =
    new ConcurrentSkipListSet<byte[]>(Bytes.BYTES_COMPARATOR);
  private List<Cell> expected = new ArrayList<Cell>();
  private long id = System.currentTimeMillis();
  private Get get = new Get(row);
  private final static HBaseTestingUtility TEST_UTIL = new HBaseTestingUtility();
  private final String DIR = TEST_UTIL.getDataTestDir("TestHMobStore").toString();

  /**
   * Setup
   * @throws Exception
   */
  @Before
  public void setUp() throws Exception {
    qualifiers.add(qf1);
    qualifiers.add(qf3);
    qualifiers.add(qf5);

    Iterator<byte[]> iter = qualifiers.iterator();
    while(iter.hasNext()){
      byte [] next = iter.next();
      expected.add(new KeyValue(row, family, next, 1, value));
      get.addColumn(family, next);
      get.setMaxVersions(); // all versions.
    }
  }

  private void init(String methodName, Configuration conf, boolean testStore)
  throws IOException {
    hcd = new HColumnDescriptor(family);
    hcd.setValue(MobConstants.IS_MOB, Bytes.toBytes(Boolean.TRUE));
    hcd.setValue(MobConstants.MOB_THRESHOLD, Bytes.toBytes(3L));
    hcd.setMaxVersions(4);
    init(methodName, conf, hcd, testStore);
  }

  private void init(String methodName, Configuration conf,
      HColumnDescriptor hcd, boolean testStore) throws IOException {
    conf.setInt("hfile.format.version", 3);
    HTableDescriptor htd = new HTableDescriptor(TableName.valueOf(table));
    init(methodName, conf, htd, hcd, testStore);
  }

  private void init(String methodName, Configuration conf, HTableDescriptor htd,
      HColumnDescriptor hcd, boolean testStore) throws IOException {
    //Setting up tje Region and Store
    Path basedir = new Path(DIR+methodName);
    Path tableDir = FSUtils.getTableDir(basedir, htd.getTableName());
    String logName = "logs";
    Path logdir = new Path(basedir, logName);
    FileSystem fs = FileSystem.get(conf);
    fs.delete(logdir, true);

    htd.addFamily(hcd);
    HRegionInfo info = new HRegionInfo(htd.getTableName(), null, null, false);
    final Configuration walConf = new Configuration(conf);
    FSUtils.setRootDir(walConf, basedir);
    final WALFactory wals = new WALFactory(walConf, null, methodName);
    region = new HRegion(tableDir, wals.getWAL(info.getEncodedNameAsBytes()), fs, conf,
        info, htd, null);

    store = new HMobStore(region, hcd, conf);
    if(testStore) {
      init(conf, hcd);
    }
  }

  private void init(Configuration conf, HColumnDescriptor hcd)
      throws IOException {
    Path basedir = FSUtils.getRootDir(conf);
    fs = FileSystem.get(conf);
    Path homePath = new Path(basedir, Bytes.toString(family) + Path.SEPARATOR
        + Bytes.toString(family));
    fs.mkdirs(homePath);

    KeyValue key1 = new KeyValue(row, family, qf1, 1, value);
    KeyValue key2 = new KeyValue(row, family, qf2, 1, value);
    KeyValue key3 = new KeyValue(row2, family, qf3, 1, value2);
    KeyValue[] keys = new KeyValue[] { key1, key2, key3 };
    int maxKeyCount = keys.length;
    StoreFile.Writer mobWriter = store.createWriterInTmp(currentDate,
        maxKeyCount, hcd.getCompactionCompression(), region.getStartKey());
    mobFilePath = mobWriter.getPath();

    mobWriter.append(key1);
    mobWriter.append(key2);
    mobWriter.append(key3);
    mobWriter.close();

    long valueLength1 = key1.getValueLength();
    long valueLength2 = key2.getValueLength();
    long valueLength3 = key3.getValueLength();

    String targetPathName = MobUtils.formatDate(currentDate);
    byte[] referenceValue =
            Bytes.toBytes(targetPathName + Path.SEPARATOR
                + mobFilePath.getName());
    byte[] newReferenceValue1 = Bytes.add(Bytes.toBytes(valueLength1), referenceValue);
    byte[] newReferenceValue2 = Bytes.add(Bytes.toBytes(valueLength2), referenceValue);
    byte[] newReferenceValue3 = Bytes.add(Bytes.toBytes(valueLength3), referenceValue);
    seekKey1 = new KeyValue(row, family, qf1, Long.MAX_VALUE, newReferenceValue1);
    seekKey2 = new KeyValue(row, family, qf2, Long.MAX_VALUE, newReferenceValue2);
    seekKey3 = new KeyValue(row2, family, qf3, Long.MAX_VALUE, newReferenceValue3);
  }

  /**
   * Getting data from memstore
   * @throws IOException
   */
  @Test
  public void testGetFromMemStore() throws IOException {
    final Configuration conf = HBaseConfiguration.create();
    init(name.getMethodName(), conf, false);

    //Put data in memstore
    this.store.add(new KeyValue(row, family, qf1, 1, value));
    this.store.add(new KeyValue(row, family, qf2, 1, value));
    this.store.add(new KeyValue(row, family, qf3, 1, value));
    this.store.add(new KeyValue(row, family, qf4, 1, value));
    this.store.add(new KeyValue(row, family, qf5, 1, value));
    this.store.add(new KeyValue(row, family, qf6, 1, value));

    Scan scan = new Scan(get);
    InternalScanner scanner = (InternalScanner) store.getScanner(scan,
        scan.getFamilyMap().get(store.getFamily().getName()),
        0);

    List<Cell> results = new ArrayList<Cell>();
    scanner.next(results);
    Collections.sort(results, KeyValue.COMPARATOR);
    scanner.close();

    //Compare
    Assert.assertEquals(expected.size(), results.size());
    for(int i=0; i<results.size(); i++) {
      // Verify the values
      Assert.assertEquals(expected.get(i), results.get(i));
    }
  }

  /**
   * Getting MOB data from files
   * @throws IOException
   */
  @Test
  public void testGetFromFiles() throws IOException {
    final Configuration conf = TEST_UTIL.getConfiguration();
    init(name.getMethodName(), conf, false);

    //Put data in memstore
    this.store.add(new KeyValue(row, family, qf1, 1, value));
    this.store.add(new KeyValue(row, family, qf2, 1, value));
    //flush
    flush(1);

    //Add more data
    this.store.add(new KeyValue(row, family, qf3, 1, value));
    this.store.add(new KeyValue(row, family, qf4, 1, value));
    //flush
    flush(2);

    //Add more data
    this.store.add(new KeyValue(row, family, qf5, 1, value));
    this.store.add(new KeyValue(row, family, qf6, 1, value));
    //flush
    flush(3);

    Scan scan = new Scan(get);
    InternalScanner scanner = (InternalScanner) store.getScanner(scan,
        scan.getFamilyMap().get(store.getFamily().getName()),
        0);

    List<Cell> results = new ArrayList<Cell>();
    scanner.next(results);
    Collections.sort(results, KeyValue.COMPARATOR);
    scanner.close();

    //Compare
    Assert.assertEquals(expected.size(), results.size());
    for(int i=0; i<results.size(); i++) {
      Assert.assertEquals(expected.get(i), results.get(i));
    }
  }

  /**
   * Getting the reference data from files
   * @throws IOException
   */
  @Test
  public void testGetReferencesFromFiles() throws IOException {
    final Configuration conf = HBaseConfiguration.create();
    init(name.getMethodName(), conf, false);

    //Put data in memstore
    this.store.add(new KeyValue(row, family, qf1, 1, value));
    this.store.add(new KeyValue(row, family, qf2, 1, value));
    //flush
    flush(1);

    //Add more data
    this.store.add(new KeyValue(row, family, qf3, 1, value));
    this.store.add(new KeyValue(row, family, qf4, 1, value));
    //flush
    flush(2);

    //Add more data
    this.store.add(new KeyValue(row, family, qf5, 1, value));
    this.store.add(new KeyValue(row, family, qf6, 1, value));
    //flush
    flush(3);

    Scan scan = new Scan(get);
    scan.setAttribute(MobConstants.MOB_SCAN_RAW, Bytes.toBytes(Boolean.TRUE));
    InternalScanner scanner = (InternalScanner) store.getScanner(scan,
      scan.getFamilyMap().get(store.getFamily().getName()),
      0);

    List<Cell> results = new ArrayList<Cell>();
    scanner.next(results);
    Collections.sort(results, KeyValue.COMPARATOR);
    scanner.close();

    //Compare
    Assert.assertEquals(expected.size(), results.size());
    for(int i=0; i<results.size(); i++) {
      Cell cell = results.get(i);
      Assert.assertTrue(MobUtils.isMobReferenceCell(cell));
    }
  }

  /**
   * Getting data from memstore and files
   * @throws IOException
   */
  @Test
  public void testGetFromMemStoreAndFiles() throws IOException {

    final Configuration conf = HBaseConfiguration.create();

    init(name.getMethodName(), conf, false);

    //Put data in memstore
    this.store.add(new KeyValue(row, family, qf1, 1, value));
    this.store.add(new KeyValue(row, family, qf2, 1, value));
    //flush
    flush(1);

    //Add more data
    this.store.add(new KeyValue(row, family, qf3, 1, value));
    this.store.add(new KeyValue(row, family, qf4, 1, value));
    //flush
    flush(2);

    //Add more data
    this.store.add(new KeyValue(row, family, qf5, 1, value));
    this.store.add(new KeyValue(row, family, qf6, 1, value));

    Scan scan = new Scan(get);
    InternalScanner scanner = (InternalScanner) store.getScanner(scan,
        scan.getFamilyMap().get(store.getFamily().getName()),
        0);

    List<Cell> results = new ArrayList<Cell>();
    scanner.next(results);
    Collections.sort(results, KeyValue.COMPARATOR);
    scanner.close();

    //Compare
    Assert.assertEquals(expected.size(), results.size());
    for(int i=0; i<results.size(); i++) {
      Assert.assertEquals(expected.get(i), results.get(i));
    }
  }

  /**
   * Getting data from memstore and files
   * @throws IOException
   */
  @Test
  public void testMobCellSizeThreshold() throws IOException {

    final Configuration conf = HBaseConfiguration.create();

    HColumnDescriptor hcd;
    hcd = new HColumnDescriptor(family);
    hcd.setValue(MobConstants.IS_MOB, Bytes.toBytes(Boolean.TRUE));
    hcd.setValue(MobConstants.MOB_THRESHOLD, Bytes.toBytes(100L));
    hcd.setMaxVersions(4);
    init(name.getMethodName(), conf, hcd, false);

    //Put data in memstore
    this.store.add(new KeyValue(row, family, qf1, 1, value));
    this.store.add(new KeyValue(row, family, qf2, 1, value));
    //flush
    flush(1);

    //Add more data
    this.store.add(new KeyValue(row, family, qf3, 1, value));
    this.store.add(new KeyValue(row, family, qf4, 1, value));
    //flush
    flush(2);

    //Add more data
    this.store.add(new KeyValue(row, family, qf5, 1, value));
    this.store.add(new KeyValue(row, family, qf6, 1, value));
    //flush
    flush(3);

    Scan scan = new Scan(get);
    scan.setAttribute(MobConstants.MOB_SCAN_RAW, Bytes.toBytes(Boolean.TRUE));
    InternalScanner scanner = (InternalScanner) store.getScanner(scan,
      scan.getFamilyMap().get(store.getFamily().getName()),
      0);

    List<Cell> results = new ArrayList<Cell>();
    scanner.next(results);
    Collections.sort(results, KeyValue.COMPARATOR);
    scanner.close();

    //Compare
    Assert.assertEquals(expected.size(), results.size());
    for(int i=0; i<results.size(); i++) {
      Cell cell = results.get(i);
      //this is not mob reference cell.
      Assert.assertFalse(MobUtils.isMobReferenceCell(cell));
      Assert.assertEquals(expected.get(i), results.get(i));
      Assert.assertEquals(100, MobUtils.getMobThreshold(store.getFamily()));
    }
  }

  @Test
  public void testCommitFile() throws Exception {
    final Configuration conf = HBaseConfiguration.create();
    init(name.getMethodName(), conf, true);
    String targetPathName = MobUtils.formatDate(new Date());
    Path targetPath = new Path(store.getPath(), (targetPathName
        + Path.SEPARATOR + mobFilePath.getName()));
    fs.delete(targetPath, true);
    Assert.assertFalse(fs.exists(targetPath));
    //commit file
    store.commitFile(mobFilePath, targetPath);
    Assert.assertTrue(fs.exists(targetPath));
  }

  @Test
  public void testResolve() throws Exception {
    final Configuration conf = HBaseConfiguration.create();
    init(name.getMethodName(), conf, true);
    String targetPathName = MobUtils.formatDate(currentDate);
    Path targetPath = new Path(store.getPath(), targetPathName);
    store.commitFile(mobFilePath, targetPath);
    //resolve
    Cell resultCell1 = store.resolve(seekKey1, false);
    Cell resultCell2 = store.resolve(seekKey2, false);
    Cell resultCell3 = store.resolve(seekKey3, false);
    //compare
    Assert.assertEquals(Bytes.toString(value),
        Bytes.toString(CellUtil.cloneValue(resultCell1)));
    Assert.assertEquals(Bytes.toString(value),
        Bytes.toString(CellUtil.cloneValue(resultCell2)));
    Assert.assertEquals(Bytes.toString(value2),
        Bytes.toString(CellUtil.cloneValue(resultCell3)));
  }

  /**
   * Flush the memstore
   * @param storeFilesSize
   * @throws IOException
   */
  private void flush(int storeFilesSize) throws IOException{
    this.store.snapshot();
    flushStore(store, id++);
    Assert.assertEquals(storeFilesSize, this.store.getStorefiles().size());
    Assert.assertEquals(0, ((DefaultMemStore)this.store.memstore).cellSet.size());
  }

  /**
   * Flush the memstore
   * @param store
   * @param id
   * @throws IOException
   */
  private static void flushStore(HMobStore store, long id) throws IOException {
    StoreFlushContext storeFlushCtx = store.createFlushContext(id);
    storeFlushCtx.prepare();
    storeFlushCtx.flushCache(Mockito.mock(MonitoredTask.class));
    storeFlushCtx.commit(Mockito.mock(MonitoredTask.class));
  }
}
