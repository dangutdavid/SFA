package sfa.test;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import sfa.classification.ParallelFor;
import sfa.index.SFATrie;
import sfa.index.SortedListMap;
import sfa.timeseries.TimeSeries;
import sfa.timeseries.TimeSeriesLoader;
import sfa.transformation.SFA;
import sfa.transformation.SFA.HistogramType;

public class SFABulkLoad {

  static String bucketDir = "./tmp/";
  static ExecutorService serializerExec = Executors.newFixedThreadPool(2); // serialize access to the disk
  static ExecutorService transformExec = Executors.newFixedThreadPool(4); // parallel SFA transformation
  
  static LinkedList<Future<Long>> futures = new LinkedList<>();

  public static void testParallelWrite() throws IOException {
    int l = 16; // SFA word length ( & dimensionality of the index)
    int leafThreshold = 1000; // number of subsequences in each leaf node
    byte symbols = SFATrie.symbols;
        
    if (!new File(bucketDir).exists()) {
      System.out.println("Creating temp directory...");
      new File(bucketDir).mkdir();
    }
    
    System.out.println("Loading/generating Time Series...");
    
    // samples to be indexed
//    TimeSeries timeSeries = TimeSeriesLoader.readSampleSubsequence(new File("./datasets/indexing/sample_lightcurves.txt"));
    TimeSeries timeSeries = TimeSeriesLoader.generateRandomWalkData(40 * 1000000, new Random(1));    
    System.out.println("Sample DS size:\t" + timeSeries.getLength());
    
    // query subsequences
    TimeSeries[] timeSeries2 = TimeSeriesLoader.readSamplesQuerySeries(new File("./datasets/indexing/query_lightcurves.txt"));
    int windowLength = timeSeries2[0].getLength(); 
    System.out.println("Query DS size:\t" + windowLength);
    
    // process data in chunks of 'chunkSize' and create one index each
    int chunkSize = 1000000;
    System.out.println("Chunk size:\t" + chunkSize);
    
    // the depth of the tree to use for bulk loading:
    //    depth 1 => 8^1 buckets 
    //    depth 2 => 8^2 buckets
    //    ...
    //    depth i => symbols ^ i buckets 
    int trieDepth = (int)(Math.round(Math.log(timeSeries.getLength() / chunkSize) / Math.log(8))); 
    System.out.println("Using trie depth:\t" + trieDepth + " (" + (int) Math.pow(8,trieDepth) + " buckets)");
    
    Runtime runtime = Runtime.getRuntime();
    long mem = runtime.totalMemory();

    SFA sfa = new SFA(HistogramType.EQUI_FREQUENCY);
    sfa.fitWindowing(new TimeSeries[] {timeSeries}, windowLength, l, symbols, true, true);
    //		sfa.printBins();

    SerializedStreams dataStream = new SerializedStreams(trieDepth);
    long time = System.currentTimeMillis();        
   
    // transform al approximations
    
    // create sliding windows
    int BLOCKS = (int)Math.ceil(timeSeries.getLength()/chunkSize);
    ParallelFor.withIndex(transformExec, BLOCKS, new ParallelFor.Each() {
      long time = System.currentTimeMillis();        

      @Override
      public void run(int id, AtomicInteger processed) {
        for (int i = 0, a = 0; i < timeSeries.getLength(); i+=chunkSize, a++) {
          if (a % BLOCKS == id) {
            System.out.println("Transforming Chunk: " + (a+1));
            TimeSeries subsequence = timeSeries.getSubsequence(i, chunkSize);
            double[][] words = sfa.transformWindowingDouble(subsequence, l);
            for (int pos = 0; pos < words.length; pos++) {
              double[] word = words[pos];
              byte[] w = sfa.quantizationByte(word);
              dataStream.addToPartition(w, word, i+pos, trieDepth);
            }
            // wait for all futures to finish
            long bytesWritten = 0;
            while (!futures.isEmpty()) {
              try {
                bytesWritten = futures.remove().get();     
              } catch (Exception e) {
                e.printStackTrace();
              }
            }
            System.out.println("\tavg write speed: " + (bytesWritten / (System.currentTimeMillis() - time))  + " kb/s");
          }      
        }
      }
    });
        
//    for (int i = 0, a = 0; i < timeSeries.getLength(); i+=chunkSize, a++) {
//      System.out.println("Transforming Chunk: " + (a+1));
//      TimeSeries subsequence = timeSeries.getSubsequence(i, chunkSize);
//      double[][] words = sfa.transformWindowingDouble(subsequence, l);
//      for (int pos = 0; pos < words.length; pos++) {
//        double[] word = words[pos];
//        byte[] w = sfa.quantizationByte(word);
//        dataStream.addToPartition(w, word, i+pos, trieDepth);
//      }
//
//      // wait for all futures to finish
//      long bytesWritten = 0;
//      while (!futures.isEmpty()) {
//        try {
//          bytesWritten = futures.remove().get();     
//        } catch (Exception e) {
//          e.printStackTrace();
//        }
//      }
//      System.out.println("\tavg write speed: " + (bytesWritten / (System.currentTimeMillis() - time))  + " kb/s");
//    }

    // wait for all approximations to finish
    dataStream.setFinished();

    // process each bucket
    SFATrie index = null;
    
    System.out.println("Building and merging Trees:");
    File directory = new File(bucketDir);

    // create an index for each bucket and merge the indices
    for (File bucket : directory.listFiles()) {
      if (bucket.isFile() && bucket.getName().contains("bucket")) {
        time = System.currentTimeMillis();
        List<SFATrie.Approximation[]> windows = readFromFile(bucket);
        if (!windows.isEmpty()) { 
          SFATrie trie = new SFATrie(l, leafThreshold, sfa);
          trie.buildIndex(windows, trieDepth, windowLength);
        
          if (index == null) {
            index = trie;
          }
          else {
            index.mergeTrees(trie);
          }
  
          System.out.println("Merging done in "
              + (System.currentTimeMillis() - time) + " ms. " 
              + "\t Elements: " + index.getSize() 
              + "\t Height: " + index.getHeight());
        }
      }
    }
    
    // path compression
    index.compress(true);

    // add the raw data to the trie
    index.setTimeSeries(timeSeries, windowLength);
    
    index.printStats();

    // store index
    System.out.println("Writing index to disk...");
    File location = new File("./tmp/sfatrie.idx");
//    location.deleteOnExit();
    index.writeToDisk(location);

    // GC
    performGC();
    System.out.println("Memory: " + ((runtime.totalMemory() - mem) / (1048576l)) + " MB (rough estimate)");

    int k = 1;
    
    int size = (timeSeries.getData().length-windowLength)+1;
    double[] means = new double[size];
    double[] stds = new double[size];
    TimeSeries.calcIncreamentalMeanStddev(windowLength, timeSeries, means, stds);
    
    for (int i = 0; i < timeSeries2.length; i++) {
      System.out.println((i+1) + ". Query");
      TimeSeries query = timeSeries2[i];
      
      time = System.currentTimeMillis();
      SortedListMap<Double, Integer> result = index.searchNearestNeighbor(query, k);
      time = System.currentTimeMillis() - time;
      System.out.println("\tSFATree:" + (time/1000.0) + "s");

      List<Double> distances = result.keys();
      System.out.println("\tTS seen: " +index.getTimeSeriesRead());
      System.out.println("\tLeaves seen " + index.getIoTimeSeriesRead());
      System.out.println("\tNodes seen " +  index.getBlockRead());
      index.resetIoCosts();

      // compare with nearest neighbor search!
      time = System.currentTimeMillis();
      double resultDistance = Double.MAX_VALUE;
      for (int ww = 0; ww < size; ww++) { // faster than reevaluation in for loop
        double distance = getEuclideanDistance(timeSeries, query, means[ww], stds[ww], resultDistance, ww);
        resultDistance = Math.min(distance, resultDistance);
      }
      time = System.currentTimeMillis() - time;
      System.out.println("\tEuclidean:" + (time/1000.0) + "s");

      if (distances.get(0) != resultDistance) {
        System.out.println("\tError! Distances do not match: " + resultDistance + "\t" + distances.get(0));
      }
      else {
        System.out.println("\tDistance is ok");
      }
    }

    System.out.println("All ok...");
  }

  public static void performGC() {
    try {
      System.gc();
      Thread.sleep(10);
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
  }

  public static double getEuclideanDistance(
      TimeSeries ts,
      TimeSeries q,
      double meanTs,
      double stdTs,
      double minValue,
      int w
      ) {

    // 1 divided by stddev for fastert calculations
    stdTs = (stdTs>0? 1.0 / stdTs : 1.0);

    double distance = 0.0;
    double[] tsData = ts.getData();
    double[] qData = q.getData();

    for (int ww = 0; ww < qData.length; ww++) {
      double value1 = (tsData[w+ww]-meanTs) * stdTs;
      double value = qData[ww] - value1;
      distance += value*value;

      // early abandoning
      if (distance >= minValue) {
        return Double.MAX_VALUE;
      }
    }

    return distance;
  }

  protected static List<SFATrie.Approximation[]> readFromFile(File name) {
    System.out.println("Reading from : " + name.toString());
    long count = 0;
    List<SFATrie.Approximation[]> data = new ArrayList<>();
    try (ObjectInputStream in = new ObjectInputStream(new BufferedInputStream(new FileInputStream(name)))) {
      SFATrie.Approximation[] d = null;
      while ((d = (SFATrie.Approximation[]) in.readObject()) != null) {
        data.add(d);
        count += d.length;
      }
    } catch (EOFException e) {
    } catch (Exception e) {
      e.printStackTrace();
    } 
    System.out.println("\t" + count + " time series read.");

    return data;
  }

  static class SerializedStreams {   
    LinkedBlockingQueue<SFATrie.Approximation>[] wordPartitions;
    ObjectOutputStream[] partitionsStream;

    // the number of TS until the array is written to disk
    static final int minWriteToDiskLimit = 100000; 

    long[] writtenSamples;    
    long totalBytes = 0;
    double time = 0;

    @SuppressWarnings("unchecked")
    public SerializedStreams(final int useLetters) {      
      // the number of partitions to process
      int count = (int) Math.pow(SFATrie.symbols, useLetters);     
      this.wordPartitions = new LinkedBlockingQueue[count];
      this.partitionsStream = new ObjectOutputStream[count];

      this.writtenSamples = new long[count];
      this.time = System.currentTimeMillis();

      for (int i = 0; i < this.wordPartitions.length; i++) {
        this.wordPartitions[i] = new LinkedBlockingQueue<>(minWriteToDiskLimit*2);
        this.writtenSamples[i] = 0l;
      }      
    }

    public void setFinished() {      
      // finish all data
      for (int i = 0; i < SerializedStreams.this.wordPartitions.length; i++) {
        try {        
          // copy contents
          List<SFATrie.Approximation> current = new ArrayList<>(this.wordPartitions[i].size());
          this.wordPartitions[i].drainTo(current);
          writeToDisk(current, i);
        } catch (Exception e) {
          e.printStackTrace();
        }
      }      
      // wait for all futures to finish
      while (!futures.isEmpty()) {
        try {
          futures.remove().get();          
        } catch (Exception e) {
          e.printStackTrace();
        }
      }
      // close all streams
      long totalTSwritten = 0;
      for (int i = 0; i < SerializedStreams.this.wordPartitions.length; i++) {
        try {        
          if (partitionsStream[i]!=null) {
            partitionsStream[i].close();
            totalTSwritten += writtenSamples[i];
          }
        } catch (Exception e) {
          e.printStackTrace();
        }        
      }
      System.out.println("Time series written:" + totalTSwritten);
    }

    /**
     * Adds a time series to the corresponding queue
     */
    public void addToPartition(byte[] words, double[] data, int pos, int useLetters) {
      try {
        // the bucket
        int l = getPosition(words, useLetters);
        this.wordPartitions[l].put(new SFATrie.Approximation(data, words, pos));

        // write to disk
        synchronized (this.wordPartitions[l]) {          
          if (this.wordPartitions[l].size() >= minWriteToDiskLimit) {            
            final List<SFATrie.Approximation> current = new ArrayList<>(this.wordPartitions[l].size());
            this.wordPartitions[l].drainTo(current);
            
            futures.add(serializerExec.submit(new Callable<Long>() {
              @Override
              public Long call() throws Exception {
                writeToDisk(current, l);
                totalBytes += current.size() * 20 * 8;
                return totalBytes;
              }
            }));
          }

        }                   
      } catch (Exception e) {
        e.printStackTrace();
      }
    }

    protected int getPosition(byte[] word, int useLetters) {
      int id = word[0];
      if (useLetters > 1) {
        id = id * SFATrie.symbols + word[1];
      }
      if (useLetters > 2) {
        id = id * SFATrie.symbols + word[2];        
      }
      return id;
    }

    protected void writeToDisk(List<SFATrie.Approximation> current, int letter) throws FileNotFoundException, IOException {      
      if (!current.isEmpty()) {   
        if (partitionsStream[letter] == null) {
          String fileName = bucketDir + letter + ".bucket";
          File file = new File(fileName);
          file.deleteOnExit();
          partitionsStream[letter] 
              = new ObjectOutputStream(new BufferedOutputStream(new FileOutputStream(file, false), 1048576*8 /* 8kb */));         
        }    
        
        partitionsStream[letter].writeUnshared(current.toArray(new SFATrie.Approximation[]{}));
        partitionsStream[letter].reset(); // reset the references to the objects
                
        try {
          Thread.sleep(100);
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
        
        this.writtenSamples[letter] += current.size();        
      }
    }
  }

  public static void main(String argv[]) throws IOException {
    try {
      testParallelWrite();
    } finally {
      serializerExec.shutdown();
      transformExec.shutdown();
    }
  }

}
