package org.wikipedia.miner.extract.steps;

import java.io.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/*import opennlp.tools.sentdetect.SentenceDetectorME;
import opennlp.tools.sentdetect.SentenceModel;
import opennlp.tools.util.Span;*/

import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.filecache.DistributedCache;
import org.apache.hadoop.fs.*;
import org.apache.hadoop.io.*;
import org.apache.hadoop.mapred.*;
import org.apache.hadoop.mapred.lib.MultipleOutputs;
import org.apache.hadoop.record.CsvRecordOutput;
import org.apache.hadoop.record.Record;
import org.apache.hadoop.util.Progressable;
import org.apache.hadoop.util.Tool;
import org.apache.log4j.Logger;
//import org.wikipedia.miner.db.struct.*;
import com.scienceminer.nerd.kb.model.hadoop.*;
import org.wikipedia.miner.extract.DumpExtractor;
import org.wikipedia.miner.extract.DumpExtractor.ExtractionStep;
import org.wikipedia.miner.extract.model.*;
import org.wikipedia.miner.extract.model.struct.ExLabel;
import org.wikipedia.miner.extract.model.struct.ExSenseForLabel;
import org.wikipedia.miner.extract.util.*;

//import org.wikipedia.miner.util.MarkupStripper;
import com.scienceminer.nerd.utilities.mediaWiki.MediaWikiParser;

/**
 * The third step in the extraction process.
 * 
 * This produces the following sequence files (in <i>&lt;ouput_dir&gt;/step3/</i>)
 * <ul>
 * <li><b>tempLabel-xxxxx</b> - associates label text (String) with label (ExLabel) - missing term and doc counts.</li>
 * <li><b>tempPageLink-xxxxx</b> - lists source/target pairs for page links with pages represented by Integer ids and redirects bypassed.</li>
 * <li><b>tempCategoryParent-xxxxx</b> - lists child category/parent category pairs for category links with pages represented by Integer ids.</li>
 * <li><b>tempArticleParent-xxxxx</b> - lists child article/parent category pairs for category links with pages represented by Integer ids.</li>
 * 
 * //<li><b>translations-xxxxx</b> - associates page id (Integer) with a map of translations by language code </li>
 * 
 * </ul>
 */
public class LabelSensesStep extends Configured implements Tool {

	public enum Output {tempLabel, tempPageLink, tempCategoryParent, tempArticleParent, sentenceSplits, translations, fatalErrors};

	private static String articleIdsByTitleDbFile = null;
	private static String redirectDbFile = null;

	public LabelSensesStep(String articleIdsByTitleDbFile, String redirectDbFile) {
		this.articleIdsByTitleDbFile = articleIdsByTitleDbFile;
		this.redirectDbFile = redirectDbFile;
	}

	public int run(String[] args) throws Exception {

		JobConf conf = new JobConf(LabelSensesStep.class);
		DumpExtractor.configureJob(conf, args);

		conf.setJobName("WM: gather label senses");

		conf.setOutputKeyClass(Text.class);
		conf.setOutputValueClass(ExLabel.class);

		conf.setMapperClass(LabelSensesMapper.class);
		conf.setCombinerClass(LabelSensesReducer.class);
		conf.setReducerClass(LabelSensesReducer.class);

		// set up input
		conf.setInputFormat(XmlInputFormat.class);
		conf.set(XmlInputFormat.START_TAG_KEY, "<page>");
		conf.set(XmlInputFormat.END_TAG_KEY, "</page>");
		FileInputFormat.setInputPaths(conf, conf.get(DumpExtractor.KEY_INPUT_FILE));

		//set up output
		conf.setOutputFormat(LabelOutputFormat.class);
		FileOutputFormat.setOutputPath(conf, new Path(conf.get(DumpExtractor.KEY_OUTPUT_DIR) +"/" + DumpExtractor.getDirectoryName(ExtractionStep.labelSense)));

		//set up distributed cache
		DistributedCache.addCacheFile(new Path(conf.get(DumpExtractor.KEY_OUTPUT_DIR) + "/" + DumpExtractor.OUTPUT_SITEINFO).toUri(), conf);
		DistributedCache.addCacheFile(new Path(conf.get(DumpExtractor.KEY_LANG_FILE)).toUri(), conf);
		//DistributedCache.addCacheFile(new Path(conf.get(DumpExtractor.KEY_SENTENCE_MODEL)).toUri(), conf);

		//cache page files created in 1st step, so we can look up pages by title
		Path pageStepPath = new Path(conf.get(DumpExtractor.KEY_OUTPUT_DIR) + "/" + DumpExtractor.getDirectoryName(ExtractionStep.page));
		for (FileStatus fs:FileSystem.get(conf).listStatus(pageStepPath)) {

			if (fs.getPath().getName().startsWith(PageStep.Output.tempPage.name())) {
				Logger.getLogger(LabelSensesStep.class).info("Cached page file " + fs.getPath());
				DistributedCache.addCacheFile(fs.getPath().toUri(), conf);
			}
		}

		//cache redirect files created in 2nd step, so we can look up pages by title
		Path redirectStepPath = new Path(conf.get(DumpExtractor.KEY_OUTPUT_DIR) + "/" + DumpExtractor.getDirectoryName(ExtractionStep.redirect));
		for (FileStatus fs:FileSystem.get(conf).listStatus(redirectStepPath)) {

			if (fs.getPath().getName().startsWith(RedirectStep.Output.redirectTargetsBySource.name())) {
				//Logger.getLogger(LabelSensesStep.class).info("Cached redirect file " + fs.getPath());
				DistributedCache.addCacheFile(fs.getPath().toUri(), conf);
			}
		}

		MultipleOutputs.addNamedOutput(conf, Output.tempPageLink.name(), IntRecordOutputFormat.class,
				IntWritable.class, DbLinkLocation.class);

		MultipleOutputs.addNamedOutput(conf, Output.tempCategoryParent.name(), TextOutputFormat.class,
				IntWritable.class, IntWritable.class);

		MultipleOutputs.addNamedOutput(conf, Output.tempArticleParent.name(), TextOutputFormat.class,
				IntWritable.class, IntWritable.class);

		/*MultipleOutputs.addNamedOutput(conf, Output.sentenceSplits.name(), IntRecordOutputFormat.class,
				IntWritable.class, DbSentenceSplitList.class);*/

		/*MultipleOutputs.addNamedOutput(conf, Output.translations.name(), IntRecordOutputFormat.class,
				IntWritable.class, DbTranslations.class);*/

		MultipleOutputs.addNamedOutput(conf, Output.fatalErrors.name(), TextOutputFormat.class,
				IntWritable.class, Text.class);

		conf.set("mapred.textoutputformat.separator", ",");

		JobClient.runJob(conf);
		return 0;
	}


	/**
	 *	Takes xml markup of pages (one page element per record) and emits 
	 *		-key: redirect id
	 *		-value: redirect target id
	 */
	private static class LabelSensesMapper extends MapReduceBase implements Mapper<LongWritable, Text, Text, ExLabel> {

		private LanguageConfiguration lc;
		private SiteInfo si;

		private DumpPageParser pageParser;
		private DumpLinkParser linkParser;

		//Vector<Path> pageFiles = new Vector<Path>();
		private PagesByTitleCache pagesByTitle;
		
		//Vector<Path> redirectFiles = new Vector<Path>();
		private RedirectCache redirects = null;

		//private MarkupStripper stripper = new MarkupStripper();
		private MediaWikiParser stripper = MediaWikiParser.getInstance();

		//private SentenceDetectorME sentenceDetector;

		private MultipleOutputs mos;

		Pattern paragraphSplitPattern = Pattern.compile("\n(\\s*)[\n\\:\\*\\#]");

		@Override
		public void configure(JobConf job) {
			try {
				for (Path p:DistributedCache.getLocalCacheFiles(job)) {
					Logger.getLogger(LabelSensesMapper.class).info("Located cached file " + p.toString());

					//Logger.getLogger(LabelSensesMapper.class).info(p.getName() + " v.s " + new Path(job.get(DumpExtractor.KEY_SENTENCE_MODEL)).getName());

					/*if (p.getName().equals(new Path(job.get(DumpExtractor.KEY_SENTENCE_MODEL)).getName())) {
						Logger.getLogger(LabelSensesMapper.class).info("Located cached sentence model " + p.toString());
						File sentenceModelFile = new File(p.toString());
						
						InputStream sentenceModelStream = new FileInputStream(sentenceModelFile);
						SentenceModel model = null;
						try {
						  model = new SentenceModel(sentenceModelStream);
						}
						catch (IOException e) {
						  e.printStackTrace();
						}
						finally {
						  if (sentenceModelStream != null) {
						    try {
						    	sentenceModelStream.close();
						    }
						    catch (IOException e) {
						    }
						  }
						}

						sentenceDetector = new SentenceDetectorME(model);
					}*/

					if (p.getName().equals(new Path(DumpExtractor.OUTPUT_SITEINFO).getName())) {
						si = new SiteInfo(p);
					}

					if (p.getName().equals(new Path(job.get(DumpExtractor.KEY_LANG_FILE)).getName())) {
						lc = new LanguageConfiguration(job.get(DumpExtractor.KEY_LANG_CODE), p);
					}

					/*if (p.getName().startsWith(PageStep.Output.tempPage.name())) {
						Logger.getLogger(LabelSensesMapper.class).info("Located cached page file " + p.toString());
						pageFiles.add(p);
					}

					if (p.getName().startsWith(RedirectStep.Output.redirectTargetsBySource.name())) {
						Logger.getLogger(LabelSensesMapper.class).info("Located cached redirect file " + p.toString());
						redirectFiles.add(p);
					}*/
				}

				if (si == null) 
					throw new Exception("Could not locate '" + DumpExtractor.OUTPUT_SITEINFO + "' in DistributedCache");

				/*if (lc == null) 
					throw new Exception("Could not locate '" + job.get(DumpExtractor.KEY_LANG_FILE) + "' in DistributedCache");*/

				/*if (sentenceDetector == null) 
					throw new Exception("Could not load sentence model '" + job.get(DumpExtractor.KEY_SENTENCE_MODEL) + "' from DistributedCache");*/


				//if (pageFiles.isEmpty())
				//	throw new Exception("Could not gather page summary files produced in step 1");

				//if (redirectFiles.isEmpty())
				//	throw new Exception("Could not gather redirect summary files produced in step 2");

				pageParser = new DumpPageParser(lc, si);
				linkParser = new DumpLinkParser(lc, si);

				mos = new MultipleOutputs(job);
				
				//articlesByTitle = PagesByTitleCache.getArticlesCache();
				//categoriesByTitle = PagesByTitleCache.getCategoriesCache();

				//categoriesByTitle = PagesByTitleCache.getInstance();

				//if (!categoriesByTitle.isLoadedCategories())
				//	categoriesByTitle.loadCategories(pageFiles, null);
				pagesByTitle = new PagesByTitleCache(articleIdsByTitleDbFile, job.get(DumpExtractor.KEY_LANG_CODE));

				//if(!redirects.isLoaded()) 
				//	redirects.load(redirectFiles, null);
				redirects = new RedirectCache(redirectDbFile, job.get(DumpExtractor.KEY_LANG_CODE));	

			} catch (Exception e) {
				Logger.getLogger(LabelSensesMapper.class).error("Could not configure mapper", e);
				System.exit(1);
			}
		}

		@Override
		public void map(LongWritable key, Text value, OutputCollector<Text, ExLabel> output, Reporter reporter) throws IOException {

			/*if (!articlesByTitle.isLoaded())
				articlesByTitle.load(pageFiles, reporter);
			
			if (!categoriesByTitle.isLoaded())
				categoriesByTitle.load(pageFiles, reporter);*/	
			
			DumpPage page = null;

			try {				
				//set up articlesByTitle and categoriesByTitle, if this hasn't been done already
				//this is done during map rather than configure, so that we can report progress
				//and stop hadoop from declaring a timeout.
				
				page = pageParser.parsePage(value.toString());
				if (page != null) {

					// build up all the anchors locally for this document before emitting, to maintain docCounts and occCounts
					HashMap<String, ExLabel> labels = new HashMap<String, ExLabel>();

					// build up all links and locations locally for this document before emitting, so they are sorted and grouped properly 
					// (this doesn't go though a reduce phase)
					//TreeMap<Integer, ArrayList<Integer>> outLinksAndLocations = new TreeMap<Integer, ArrayList<Integer>>();
					
					// we just need the links out
					List<Integer> outLinks = new ArrayList<Integer>();
					
					// build up translations tree map
					//TreeMap<String, String> translationsByLangCode = new TreeMap<String, String>();

					ExLabel label;

					switch(page.getType()) {

						case article :
						case disambiguation :
							// add association from this article title to article.
							label = new ExLabel(0,0,0,0,new TreeMap<Integer, ExSenseForLabel>());
							label.getSensesById().put(page.getId(), new ExSenseForLabel(0, 0, true, false));	
							labels.put(page.getTitle(), label);

						case category :

							String markup = page.getMarkup();
							//markup = stripper.stripAllButInternalLinksAndEmphasis(markup, ' ');
							//markup = stripper.toTextWithInternalLinksEmphasisOnly(markup, lc.getLangCode());
							markup = stripper.toTextWithInternalLinksAndCategoriesOnly(markup, lc.getLangCode());
							gatherCategoryLinksAndTranslations(page, markup, reporter);
							
							//markup = stripper.stripNonArticleInternalLinks(markup, ' ');
							// note PL: not sure this is required
							//markup = stripper.toTextWithInternalLinksArticlesOnly(markup, DumpExtractor.KEY_LANG_CODE);

							//TreeSet<Integer> sentenceSplits = collectSentenceSplits(page.getId(), markup, reporter);

							//int sentenceIndex = 0;
							//int lastPos = 0;
							//int currPos = 0;
							//for (int currPos : sentenceSplits) {
								//processSentence(markup.substring(lastPos, currPos), sentenceIndex, page, labels, outLinksAndLocations, reporter);
							processSentence(markup, 0, page, labels, outLinks, reporter);
								//lastPos = currPos;
								//sentenceIndex ++;

								//reporter.progress();
							//}

							//processSentence(markup.substring(lastPos), sentenceIndex, page, labels, outLinksAndLocations, reporter);

							break;
						case redirect :

							// add association from this redirect title to target.
							int targetId = redirects.getTargetId(page.getTarget(), pagesByTitle);
							if (targetId != -1) {
								label = new ExLabel(0,0,0,0,new TreeMap<Integer, ExSenseForLabel>());
								label.getSensesById().put(targetId, new ExSenseForLabel(0, 0, false, true));

								labels.put(page.getTitle(), label);
							}
							break;
					}

					// now emit all of the labels we have gathered
					for (Map.Entry<String,ExLabel> entry:labels.entrySet()) {
						output.collect(new Text(entry.getKey()), entry.getValue());
					}		

					// now emit all of the outlinks that we have gathered
					//for (Map.Entry<Integer,ArrayList<Integer>> entry:outLinksAndLocations.entrySet()) {
					Collections.sort(outLinks);
					ArrayList<Integer> dummyLocation = new ArrayList<Integer>();
					dummyLocation.add(new Integer(0));
					for(Integer link : outLinks) {
						mos.getCollector(Output.tempPageLink.name(), reporter).collect(new IntWritable(page.getId()), new DbLinkLocation(link, dummyLocation));
					}

					// now emit collected translations
					//if (!translationsByLangCode.isEmpty())
					//	mos.getCollector(Output.translations.name(), reporter).collect(new IntWritable(page.getId()), new DbTranslations(translationsByLangCode));
				}

			} catch (Exception e) {
				Logger.getLogger(LabelSensesMapper.class).error("Caught exception", e);

				StringWriter sw = new StringWriter();
				PrintWriter pw = new PrintWriter(sw);

				e.printStackTrace(pw);

				if (page != null) {
					mos.getCollector(Output.fatalErrors.name(), reporter).collect(new IntWritable(page.getId()), new Text(sw.toString().replace('\n', ';')));
				} else {
					mos.getCollector(Output.fatalErrors.name(), reporter).collect(new IntWritable(-1), new Text(sw.toString().replace('\n', ';')));
				}

			}
		}


		/*private TreeSet<Integer> collectSentenceSplits(int pageId, String markup, Reporter reporter) throws IOException {

			TreeSet<Integer> sentenceSplits = new TreeSet<Integer> ();

			//mask links so that it is impossible to split on any punctuation within a link.
			String markup_linksMasked = stripper.stripRegions(markup, stripper.gatherComplexRegions(markup, "\\[\\[", "\\]\\]"), 'a');

			//also mask content in brackets, so it is impossible to split within these. 
			markup_linksMasked = stripper.stripRegions(markup_linksMasked, stripper.gatherComplexRegions(markup_linksMasked, "\\(", "\\)"), 'a');

			//add all splits detected by OpenNLP sentenceDetector
			for(Span span:sentenceDetector.sentPosDetect(markup_linksMasked))
				sentenceSplits.add(span.getEnd());

			//add all splits detected in markup (multiple newlines, or lines starting with indent or list marker)

			Matcher m = paragraphSplitPattern.matcher(markup_linksMasked);

			int lastPos = 0;
			while (m.find()) {
				int pos = m.start();

				if (markup_linksMasked.substring(lastPos, pos).trim().length() > 0)
					sentenceSplits.add(pos);

				lastPos = pos;
			}

			// collect sentence splits
			if (sentenceSplits.size() > 0) {
				ArrayList<Integer> ss = new ArrayList<Integer>();
				for (int s : sentenceSplits)
					ss.add(s);

				mos.getCollector(Output.sentenceSplits.name(), reporter).collect(new IntWritable(pageId), new DbSentenceSplitList(ss));
			}

			return sentenceSplits;
		}*/

		//private void processSentence(String sentence, int sentenceIndex, DumpPage page, HashMap<String, ExLabel> labels, TreeMap<Integer, ArrayList<Integer>> outLinksAndLocations, Reporter reporter) throws Exception {
		private void processSentence(String sentence, int sentenceIndex, DumpPage page, 
				HashMap<String, ExLabel> labels, List<Integer> outLinks, Reporter reporter) throws Exception {
			ExLabel label = null;
			Vector<int[]> linkRegions = Util.gatherComplexRegions(sentence, "\\[\\[", "\\]\\]");

			for(int[] linkRegion: linkRegions) {
				String linkMarkup = sentence.substring(linkRegion[0]+2, linkRegion[1]-2);
				DumpLink link = null;
				try {
					link = linkParser.parseLink(linkMarkup);
				} catch (Exception e) {
					Logger.getLogger(LabelSensesMapper.class).warn("Could not parse link markup '" + linkMarkup + "'");
				}

				if ((link != null) && (link.getTargetNamespace() == SiteInfo.MAIN_KEY)) {
					int targetId = redirects.getTargetId(link.getTargetTitle(), pagesByTitle); 
					if (targetId != -1) {
						label = labels.get(link.getAnchor());

						if (label == null) {
							label = new ExLabel(1,1,0,0,new TreeMap<Integer, ExSenseForLabel>());
							label.getSensesById().put(targetId, new ExSenseForLabel(1, 1, false, false));
						} else {
							ExSenseForLabel sense = label.getSensesById().get(targetId);
							if (sense == null) {
								sense = new ExSenseForLabel(1, 1, false, false);											
							} else {
								sense.setLinkDocCount(1);
								sense.setLinkOccCount(sense.getLinkOccCount() + 1);									
							}

							label.setLinkOccCount(label.getLinkOccCount() + 1);
							label.getSensesById().put(targetId, sense);
						}

						labels.put(link.getAnchor(), label);
						//ArrayList<Integer> locations = outLinksAndLocations.get(targetId); 

						//if (locations == null) 
							//locations = new ArrayList<Integer>();

						//only add sentence location if it isn't already there. This is sorted, so just check last element.
						//if (locations.isEmpty() || locations.get(locations.size()-1) < sentenceIndex) 
							//locations.add(sentenceIndex);

						//outLinksAndLocations.put(targetId, locations);
						if (!outLinks.contains(targetId))
							outLinks.add(targetId);

					} else {
						Logger.getLogger(LabelSensesMapper.class).warn("Could not resolve page link '" + link.getTargetTitle() + "'");
					}

				}				
			}
		}

		private void gatherCategoryLinksAndTranslations(DumpPage page, String markup, Reporter reporter) throws Exception {
			Vector<int[]> linkRegions = Util.gatherComplexRegions(markup, "\\[\\[", "\\]\\]");

			for(int[] linkRegion: linkRegions) {
				String linkMarkup = markup.substring(linkRegion[0]+2, linkRegion[1]-2);

				DumpLink link = null;
				try {
					link = linkParser.parseLink(linkMarkup);
				} catch (Exception e) {
					Logger.getLogger(LabelSensesMapper.class).warn("Could not parse link markup '" + linkMarkup + "'");
				}

				if (link == null)
					continue;

				/*if (link.getTargetLanguage() != null) {
					translationsByLangCode.put(link.getTargetLanguage(), link.getAnchor());
					continue;
				}*/

				if (link.getTargetNamespace() == SiteInfo.CATEGORY_KEY)  {
					//Integer parentId = categoriesByTitle.getPageId(link.getTargetTitle());
					int parentId = pagesByTitle.getCategoryId(link.getTargetTitle());
					if (parentId != -1) {
						if (page.getNamespace() == SiteInfo.CATEGORY_KEY)
							mos.getCollector(Output.tempCategoryParent.name(), reporter).collect(new IntWritable(page.getId()), new IntWritable(parentId));
						else
							mos.getCollector(Output.tempArticleParent.name(), reporter).collect(new IntWritable(page.getId()), new IntWritable(parentId));
					} else {
						Logger.getLogger(LabelSensesMapper.class).warn("Could not resolve category link '" + link.getTargetTitle() + "'");
					}
				}
			}
		}

		@Override
		public void close() throws IOException {
			pagesByTitle.close();
			redirects.close();
			
			super.close();
			mos.close();
		}
	}

	public static class LabelSensesReducer extends MapReduceBase implements Reducer<Text, ExLabel, Text, ExLabel> {

		public void reduce(Text key, Iterator<ExLabel> values, OutputCollector<Text, ExLabel> output, Reporter reporter) throws IOException {

			ExLabel label = new ExLabel(0,0,0,0,new TreeMap<Integer, ExSenseForLabel>());

			while (values.hasNext()) {

				ExLabel currLabel = values.next();

				for (Map.Entry<Integer,ExSenseForLabel> entry:currLabel.getSensesById().entrySet()) {

					ExSenseForLabel newSense = entry.getValue();
					ExSenseForLabel existingSense = label.getSensesById().get(entry.getKey());

					if (existingSense == null) {
						existingSense = newSense;
					} else {
						existingSense.setLinkOccCount(existingSense.getLinkOccCount() + newSense.getLinkOccCount());
						existingSense.setLinkDocCount(existingSense.getLinkDocCount() + newSense.getLinkDocCount());

						if (newSense.getFromRedirect())
							existingSense.setFromRedirect(true);

						if (newSense.getFromTitle())
							existingSense.setFromTitle(true);
					}

					label.getSensesById().put(entry.getKey(), existingSense);
				}

				label.setLinkDocCount(label.getLinkDocCount() + currLabel.getLinkDocCount());
				label.setLinkOccCount(label.getLinkOccCount() + currLabel.getLinkOccCount());
			}	

			output.collect(key, label);
		}
	}

	protected static class IntRecordOutputFormat extends TextOutputFormat<IntWritable, Record> {

		public RecordWriter<IntWritable, Record> getRecordWriter(FileSystem ignored,
				JobConf job,
				String name,
				Progressable progress)
				throws IOException {

			Path file = FileOutputFormat.getTaskOutputPath(job, name);
			FileSystem fs = file.getFileSystem(job);
			FSDataOutputStream fileOut = fs.create(file, progress);
			return new IntRecordWriter(fileOut);
		}

		protected static class IntRecordWriter implements RecordWriter<IntWritable, Record> {

			protected DataOutputStream outStream;

			public IntRecordWriter(DataOutputStream out) {
				this.outStream = out; 
			}

			public synchronized void write(IntWritable key, Record value) throws IOException {

				CsvRecordOutput csvOutput = new CsvRecordOutput(outStream);

				csvOutput.writeInt(key.get(), null);
				value.serialize(csvOutput); 
			}

			public synchronized void close(Reporter reporter) throws IOException {
				outStream.close();
			}
		}
	}

	protected static class LabelOutputFormat extends TextOutputFormat<Text, ExLabel> {

		public RecordWriter<Text, ExLabel> getRecordWriter(FileSystem ignored,
				JobConf job,
				String name,
				Progressable progress)
				throws IOException {

			String newName = name.replace("part", Output.tempLabel.name());

			Path file = FileOutputFormat.getTaskOutputPath(job, newName);
			FileSystem fs = file.getFileSystem(job);
			FSDataOutputStream fileOut = fs.create(file, progress);
			return new LabelRecordWriter(fileOut);
		}	

		protected static class LabelRecordWriter implements RecordWriter<Text, ExLabel> {

			protected DataOutputStream outStream;

			public LabelRecordWriter(DataOutputStream out) {
				this.outStream = out; 
			}

			public synchronized void write(Text key, ExLabel value) throws IOException {

				CsvRecordOutput csvOutput = new CsvRecordOutput(outStream);

				csvOutput.writeString(key.toString(), "label");
				value.serialize(csvOutput); 
			}

			public synchronized void close(Reporter reporter) throws IOException {
				outStream.close();
			}
		}
	}
}
