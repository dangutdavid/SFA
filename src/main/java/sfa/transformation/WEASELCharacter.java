// Copyright (c) 2017 - Patrick Schäfer (patrick.schaefer@hu-berlin.de)
// Distributed under the GLP 3.0 (See accompanying file LICENSE)
package sfa.transformation;

import java.util.concurrent.atomic.AtomicInteger;

import com.carrotsearch.hppc.LongFloatHashMap;
import com.carrotsearch.hppc.LongHashSet;
import com.carrotsearch.hppc.LongIntHashMap;
import com.carrotsearch.hppc.cursors.LongFloatCursor;
import com.carrotsearch.hppc.cursors.LongIntCursor;

import sfa.classification.Classifier.Words;
import sfa.classification.ParallelFor;
import sfa.classification.WEASELClassifier;
import sfa.timeseries.TimeSeries;

/**
 * The WEASEL-Model as published in
 * <p>
 * Schäfer, P., Leser, U.: Fast and Accurate Time Series Classification with
 * WEASEL. CIKM 2017
 */
public class WEASELCharacter {

  public int alphabetSize;
  public int maxF;

  public int[] windowLengths;
  public boolean normMean;
  public boolean lowerBounding;
  public SFA[] signature;
  public Dictionary dict;

  public final static int BLOCKS;

  static {
    Runtime runtime = Runtime.getRuntime();
    if (runtime.availableProcessors() <= 4) {
      BLOCKS = 8;
    } else {
      BLOCKS = runtime.availableProcessors();
    }

    // BLOCKS = 1; // for testing purposes
  }

  public WEASELCharacter() {
  }

  /**
   * Create a WEASEL model.
   *
   * @param maxF          Length of the SFA words
   * @param maxS          alphabet size
   * @param windowLengths the set of window lengths to use for extracting SFA
   *                      words from time series.
   * @param normMean      set to true, if mean should be set to 0 for a window
   * @param lowerBounding set to true, if the Fourier transform should be normed
   *                      (typically used to lower bound / mimic Euclidean
   *                      distance).
   */
  public WEASELCharacter(int maxF, int maxS, int[] windowLengths, boolean normMean, boolean lowerBounding) {
    this.maxF = maxF;
    this.alphabetSize = maxS;
    this.windowLengths = windowLengths;
    this.normMean = normMean;
    this.lowerBounding = lowerBounding;
    this.dict = new Dictionary();
    this.signature = new SFA[windowLengths.length];
  }

  /**
   * The WEASEL-model: a histogram of SFA word and bi-gram frequencies
   */
  public static class BagOfBigrams {
    public LongIntHashMap bob;
    public Double label;

    public BagOfBigrams(int size, Double label) {
      this.bob = new LongIntHashMap(size);
      this.label = label;
    }
  }

  /**
   * Create SFA words and bigrams for all samples
   *
   * @param samples
   * @return
   */
  public short[/* windowLength */][/* sample */][/* offset */][/* character */] createWords(final TimeSeries[] samples) {
    // create bag of words for each window queryLength
    final short[][][][] words = new short[this.windowLengths.length][samples.length][][];
    ParallelFor.withIndex(BLOCKS, new ParallelFor.Each() {
      @Override
      public void run(int id, AtomicInteger processed) {
        for (int w = 0; w < WEASELCharacter.this.windowLengths.length; w++) {
          if (w % BLOCKS == id) {
            words[w] = createWords(samples, w);
          }
        }
      }
    });
    return words;
  }

  /**
   * Create SFA words and bigrams for all samples
   *
   * @param samples
   * @return
   */
  public short[][][] createWords(final TimeSeries[] samples, final int index) {

    // SFA quantization
    if (this.signature[index] == null) {
      this.signature[index] = new SFASupervised();
      this.signature[index].fitWindowing(samples, this.windowLengths[index], this.maxF, this.alphabetSize, this.normMean, this.lowerBounding);
    }

    // create words
    final short[][][] words = new short[samples.length][][];
    for (int i = 0; i < samples.length; i++) {
      if (samples[i].getLength() >= this.windowLengths[index]) {
        words[i] = this.signature[index].transformWindowing(samples[i]);
      } else {
        words[i] = new short[][] {};
      }
    }

    return words;
  }

  /**
   * Toolbox
   * 
   * @param words
   * @return
   */
  public int[][][] fitSubwords(short[][][][] words) {

    // Subword toolbox
    // TODO transformation
    short[][][][] subwords = words;

    // TODO Basis 'alphabetsize' beachten

    // TODO mögliche Modelle
    // - BPE
    // - frequent itemsets
    // - A-priori + Intervalle
    // - character-n-grams

    // TODO Modell speichern

    return transformSubwords(words);
  }

  /**
   * Toolbox
   * 
   * @param words
   * @return
   */
  public int[][][] transformSubwords(short[][][][] words) {

    // Subword toolbox
    // TODO transformation
    short[][][][] subwords = words;
    // TODO Basis 'alphabetsize' beachten

    // TODO Modell verwenden

    // short => int
    int[][][] intWords = new int[subwords.length][][];
    for (int i = 0; i < subwords.length; i++) {
      intWords[i] = transformSubwordsOneWindow(subwords[i]);
    }

    return intWords;
  }

  /**
   * Toolbox
   * 
   * @param words
   * @return
   */
  public int[][] transformSubwordsOneWindow(short[][][] words) {
    byte neededBits = (byte) Words.binlog(this.alphabetSize);
    int[][] intWords = new int[words.length][];
    for (int j = 0; j < words.length; j++) {
      intWords[j] = new int[words[j].length];
      for (int k = 0; k < words[j].length; k++) {
        intWords[j][k] = (int) Words.createWord(words[j][k], words[j][k].length, neededBits);
      }
    }
    return intWords;
  }

  /**
   * Create words and bi-grams for all window lengths
   */
  public BagOfBigrams[] createBagOfPatterns(final int[][] wordsForWindowLength, final TimeSeries[] samples, final int w, // index of used windowSize
      final int wordLength) {
    BagOfBigrams[] bagOfPatterns = new BagOfBigrams[samples.length];

    final byte usedBits = (byte) Words.binlog(this.alphabetSize);
    final long mask = (1L << (usedBits * wordLength)) - 1L;
    int highestBit = Words.binlog(Integer.highestOneBit(WEASELClassifier.MAX_WINDOW_LENGTH)) + 1;

    // iterate all samples
    // and create a bag of pattern
    for (int j = 0; j < samples.length; j++) {
      bagOfPatterns[j] = new BagOfBigrams(wordsForWindowLength[j].length * 2, samples[j].getLabel());

      // create subsequences
      for (int offset = 0; offset < wordsForWindowLength[j].length; offset++) {
        long word = (wordsForWindowLength[j][offset] & mask) << highestBit | w;
        bagOfPatterns[j].bob.putOrAdd(word, 1, 1);

        // TODO toolbox.usesBigrams()
//        // add 2 grams
//        if (offset - this.windowLengths[w] >= 0) {
//          long prevWord = (wordsForWindowLength[j][offset - this.windowLengths[w]] & mask) << highestBit | (long) w;
//          long newWord = (prevWord << 32 | word ) << highestBit;
//          bagOfPatterns[j].bob.putOrAdd(newWord, 1, 1);
//        }
      }
    }

    return bagOfPatterns;
  }

  /**
   * Create words and bi-grams for all window lengths
   */
  public BagOfBigrams[] createBagOfPatterns(final int[][][] words, final TimeSeries[] samples, final int wordLength) {
    BagOfBigrams[] bagOfPatterns = new BagOfBigrams[samples.length];

    final byte usedBits = (byte) Words.binlog(this.alphabetSize);
    final long mask = (1L << (usedBits * wordLength)) - 1L;
    int highestBit = Words.binlog(Integer.highestOneBit(WEASELClassifier.MAX_WINDOW_LENGTH)) + 1;

    // iterate all samples
    // and create a bag of pattern
    for (int j = 0; j < samples.length; j++) {
      bagOfPatterns[j] = new BagOfBigrams(words[0][j].length * 6, samples[j].getLabel());

      // create subsequences
      for (int w = 0; w < this.windowLengths.length; w++) {
        for (int offset = 0; offset < words[w][j].length; offset++) {
          long word = (words[w][j][offset] & mask) << highestBit | w;
          bagOfPatterns[j].bob.putOrAdd(word, 1, 1);

          // TODO toolbox.usesBigrams()
//          // add 2 grams
//          if (offset - this.windowLengths[w] >= 0) {
//            long prevWord = (words[w][j][offset - this.windowLengths[w]] & mask) << highestBit | (long) w;
//            long newWord = (prevWord << 32 | word ) << highestBit;
//            bagOfPatterns[j].bob.putOrAdd(newWord, 1, 1);
//          }
        }
      }
    }

    return bagOfPatterns;
  }

  /**
   * Implementation based on:
   * https://github.com/scikit-learn/scikit-learn/blob/c957249/sklearn/feature_selection/univariate_selection.py#L170
   */
  public void filterChiSquared(final BagOfBigrams[] bob, double chi_limit) {
    // Chi2 Test
    LongIntHashMap featureCount = new LongIntHashMap(bob[0].bob.size());
    LongFloatHashMap classProb = new LongFloatHashMap(10);
    LongIntHashMap observed = new LongIntHashMap(bob[0].bob.size());

    // count number of samples with this word
    for (BagOfBigrams bagOfPattern : bob) {
      long label = bagOfPattern.label.longValue();
      for (LongIntCursor word : bagOfPattern.bob) {
        if (word.value > 0) {
          featureCount.putOrAdd(word.key, 1, 1);
          long key = label << 32 | word.key;
          observed.putOrAdd(key, 1, 1);
        }
      }
    }

    // samples per class
    for (BagOfBigrams bagOfPattern : bob) {
      long label = bagOfPattern.label.longValue();
      classProb.putOrAdd(label, 1, 1);
    }

    // chi-squared: observed minus expected occurrence
    LongHashSet chiSquare = new LongHashSet(featureCount.size());
    for (LongFloatCursor prob : classProb) {
      prob.value /= bob.length; // (float) frequencies.get(prob.key);

      for (LongIntCursor feature : featureCount) {
        long key = prob.key << 32 | feature.key;
        float expected = prob.value * feature.value;

        float chi = observed.get(key) - expected;
        float newChi = chi * chi / expected;
        if (newChi >= chi_limit && !chiSquare.contains(feature.key)) {
          chiSquare.add(feature.key);
        }
      }
    }

    for (int j = 0; j < bob.length; j++) {
      for (LongIntCursor cursor : bob[j].bob) {
        if (!chiSquare.contains(cursor.key)) {
          bob[j].bob.values[cursor.index] = 0;
        }
      }
    }
  }

  /**
   * A dictionary that maps each SFA word to an integer.
   * <p>
   * Condenses the SFA word space.
   */
  public static class Dictionary {
    public LongIntHashMap dictChi;

    public Dictionary() {
      this.dictChi = new LongIntHashMap();
    }

    public void reset() {
      this.dictChi = new LongIntHashMap();
    }

    public int getWordChi(long word) {
      int index = 0;
      if ((index = this.dictChi.indexOf(word)) > -1) {
        return this.dictChi.indexGet(index);
      } else {
        int newWord = this.dictChi.size() + 1;
        this.dictChi.put(word, newWord);
        return newWord;
      }
    }

    public int size() {
      return this.dictChi.size();
    }

    public void remap(final BagOfBigrams[] bagOfPatterns) {
      for (int j = 0; j < bagOfPatterns.length; j++) {
        LongIntHashMap oldMap = bagOfPatterns[j].bob;
        bagOfPatterns[j].bob = new LongIntHashMap();
        for (LongIntCursor word : oldMap) {
          if (word.value > 0) {
            bagOfPatterns[j].bob.put(getWordChi(word.key), word.value);
          }
        }
      }
    }

    public void filterChiSquared(final BagOfBigrams[] bagOfPatterns) {
      for (int j = 0; j < bagOfPatterns.length; j++) {
        LongIntHashMap oldMap = bagOfPatterns[j].bob;
        bagOfPatterns[j].bob = new LongIntHashMap();
        for (LongIntCursor word : oldMap) {
          if (this.dictChi.containsKey(word.key) && word.value > 0) {
            bagOfPatterns[j].bob.put(word.key, word.value);
          }
        }
      }
    }
  }
}