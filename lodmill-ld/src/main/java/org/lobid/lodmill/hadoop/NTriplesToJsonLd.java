/* Copyright 2012-2013 Fabian Steeg. Licensed under the Eclipse Public License 1.0 */

package org.lobid.lodmill.hadoop;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.filecache.DistributedCache;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.MapFile;
import org.apache.hadoop.io.MapFile.Reader;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.Utils;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.json.simple.JSONValue;
import org.lobid.lodmill.JsonLdConverter;
import org.lobid.lodmill.JsonLdConverter.Format;
import org.lobid.lodmill.hadoop.CollectSubjects.CollectSubjectsMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hp.hpl.jena.graph.Triple;
import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.ModelFactory;

/**
 * Convert RDF represented as N-Triples to JSON-LD for elasticsearch indexing.
 * 
 * @author Fabian Steeg (fsteeg)
 */
public class NTriplesToJsonLd implements Tool {

	private static final int NODES = 4; // e.g. 4 nodes in cluster
	private static final int SLOTS = 8; // e.g. 8 cores per node
	private static final String NEWLINE = "\n";
	static final String INDEX_NAME = "index.name";
	static final String INDEX_TYPE = "index.type";
	private static final Logger LOG = LoggerFactory
			.getLogger(NTriplesToJsonLd.class);
	private Configuration conf;
	private static final Configuration MAP_FILE_CONFIG = new Configuration();
	private static final String MAP_FILE_NAME = "subjects.map";

	/**
	 * @param args Generic command-line arguments passed to {@link ToolRunner}.
	 */
	public static void main(final String[] args) {
		try {
			int res = ToolRunner.run(new NTriplesToJsonLd(), args);
			System.exit(res);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public int run(String[] args) throws Exception {
		if (args.length != 5) {
			System.err
					.println("Usage: NTriplesToJsonLd"
							+ " <input path> <subjects path> <output path> <index name> <index type>");
			System.exit(-1);
		}
		createJobConfig(args);
		final Job job = new Job(conf);
		job.setNumReduceTasks(NODES * SLOTS);
		job.setJarByClass(NTriplesToJsonLd.class);
		job.setJobName("LobidToJsonLd");
		FileInputFormat.addInputPaths(job, args[0]);
		FileOutputFormat.setOutputPath(job, new Path(args[2]));
		job.setMapperClass(NTriplesToJsonLdMapper.class);
		job.setReducerClass(NTriplesToJsonLdReducer.class);
		job.setOutputKeyClass(Text.class);
		job.setOutputValueClass(Text.class);
		System.exit(job.waitForCompletion(true) ? 0 : 1);
		return 0;
	}

	private void createJobConfig(String[] args) throws IOException {
		conf = getConf();
		conf.setStrings("mapred.textoutputformat.separator", NEWLINE);
		conf.setInt("mapred.tasktracker.reduce.tasks.maximum", SLOTS);
		conf.set(INDEX_NAME, args[3]);
		conf.set(INDEX_TYPE, args[4]);
		final Path subjectMappingsPath = new Path(args[1] + "/part-r-00000");
		DistributedCache.addCacheFile(
				asZippedMapFile(subjectMappingsPath, getFileSystem()), conf);
	}

	private static FileSystem getFileSystem() throws IOException {
		return FileSystem.get(URI.create(MAP_FILE_NAME), MAP_FILE_CONFIG);
	}

	static URI asZippedMapFile(final Path subjectMappingsPath, final FileSystem fs)
			throws IOException {
		writeToMapFile(subjectMappingsPath, fs);
		final Path zippedMapFilePath = zipMapFile(fs);
		return zippedMapFilePath.toUri();
	}

	private static void writeToMapFile(final Path subjectMappingsPath,
			final FileSystem fs) throws IOException {
		try (final MapFile.Writer writer =
				new MapFile.Writer(MAP_FILE_CONFIG, fs, MAP_FILE_NAME, Text.class,
						Text.class);
				final InputStream inputStream = fs.open(subjectMappingsPath);
				final Scanner scanner = new Scanner(inputStream)) {
			while (scanner.hasNextLine()) {
				final String[] subjectAndValues = scanner.nextLine().split(" ");
				writer.append(new Text(subjectAndValues[0].trim()), new Text(
						subjectAndValues[1].trim()));
			}
		}
	}

	private static Path zipMapFile(final FileSystem fs) throws IOException,
			FileNotFoundException {
		final Path[] outputFiles =
				FileUtil.stat2Paths(fs.listStatus(new Path(MAP_FILE_NAME),
						new Utils.OutputFileUtils.OutputFilesFilter()));
		final Path zipPath = new Path("map.subjects.zip");
		try (final FSDataOutputStream fos = fs.create(zipPath);
				final ZipOutputStream zos = new ZipOutputStream(fos)) {
			add(zos, new ZipEntry("data"), fs.open(outputFiles[0]));
			add(zos, new ZipEntry("index"), fs.open(outputFiles[1]));
		}
		return zipPath;
	}

	private static void add(final ZipOutputStream zos, final ZipEntry data,
			final InputStream in) throws IOException, FileNotFoundException {
		zos.putNextEntry(data);
		IOUtils.copyBytes(in, zos, 1024);
		zos.closeEntry();
	}

	/**
	 * Map subject URIs of N-Triples to the triples.
	 * 
	 * @author Fabian Steeg (fsteeg)
	 */
	static final class NTriplesToJsonLdMapper extends
			Mapper<LongWritable, Text, Text, Text> {
		private Reader reader;

		@Override
		protected void setup(Context context) throws IOException,
				InterruptedException {
			super.setup(context);
			final Path[] localCacheFiles =
					DistributedCache.getLocalCacheFiles(context.getConfiguration());
			if (localCacheFiles != null && localCacheFiles.length == 1)
				initMapFileReader(localCacheFiles[0]);
			else
				LOG.warn("No subjects cache files found!");
		}

		private void initMapFileReader(final Path zipFile) throws IOException,
				FileNotFoundException {
			unzip(zipFile.toString(), MAP_FILE_NAME);
			reader =
					new MapFile.Reader(getFileSystem(), MAP_FILE_NAME, MAP_FILE_CONFIG);
		}

		private static void unzip(final String zipFile, final String outputFolder)
				throws FileNotFoundException, IOException {
			new File(outputFolder).mkdir();
			try (final ZipInputStream zis =
					new ZipInputStream(new FileInputStream(zipFile))) {
				for (ZipEntry ze; (ze = zis.getNextEntry()) != null; zis.closeEntry()) {
					final File newFile = new File(outputFolder, ze.getName());
					LOG.info("Unzipping to: " + newFile.getAbsoluteFile());
					try (final FileOutputStream fos = new FileOutputStream(newFile)) {
						IOUtils.copyBytes(zis, fos, 1024);
					}
				}
			}
		}

		@Override
		public void map(final LongWritable key, final Text value,
				final Context context) throws IOException, InterruptedException {
			final Triple triple = CollectSubjectsMapper.asTriple(value.toString());
			if (triple != null)
				mapSubjectsToTheirTriples(value, context, value.toString(), triple);
		}

		private void mapSubjectsToTheirTriples(final Text value,
				final Context context, final String val, final Triple triple)
				throws IOException, InterruptedException {
			final String subject =
					triple.getSubject().isBlank() ? val.substring(val.indexOf("_:"),
							val.indexOf(" ")).trim() : triple.getSubject().toString();
			if (triple.getSubject().isURI())
				context.write(new Text(wrapped(subject.trim())), value);
			if (reader != null)
				writeAdditionalSubjects(subject, value, context);
		}

		private void writeAdditionalSubjects(final String subject,
				final Text value, final Context context) throws IOException,
				InterruptedException {
			final Text res = new Text();
			reader.get(new Text(subject), res);
			if (!res.toString().isEmpty()) {
				for (String subj : res.toString().split(","))
					context.write(new Text(wrapped(subj.trim())), value);
			}
		}

		private static String wrapped(final String string) {
			return "<" + string + ">";
		}
	}

	/**
	 * Reduce all N-Triples with a common subject to a JSON-LD representation.
	 * 
	 * @author Fabian Steeg (fsteeg)
	 */
	static final class NTriplesToJsonLdReducer extends
			Reducer<Text, Text, Text, Text> {

		@Override
		public void reduce(final Text key, final Iterable<Text> values,
				final Context context) throws IOException, InterruptedException {
			final String triples = concatTriples(values);
			final String jsonLd =
					new JsonLdConverter(Format.N_TRIPLE).toJsonLd(triples);
			context.write(
					// write both with JSONValue for consistent escaping:
					new Text(JSONValue.toJSONString(createIndexMap(key, context))),
					new Text(JSONValue.toJSONString(JSONValue.parse(jsonLd))));
		}

		private static String concatTriples(final Iterable<Text> values) {
			final StringBuilder builder = new StringBuilder();
			for (Text value : values) {
				final String triple = fixInvalidUriLiterals(value);
				try {
					validate(triple);
					builder.append(triple).append(NEWLINE);
				} catch (Exception e) {
					System.err.println(String.format(
							"Could not read triple '%s': %s, skipping", triple,
							e.getMessage()));
					e.printStackTrace();
				}
			}
			return builder.toString();
		}

		private static void validate(final String val) {
			final Model model = ModelFactory.createDefaultModel();
			model.read(new StringReader(val), null, Format.N_TRIPLE.getName());
		}

		private static String fixInvalidUriLiterals(Text value) {
			return value.toString().replaceAll("\"\\s*?(http[s]?://[^\"]+)s*?\"",
					"<$1>");
		}

		private static Map<String, Map<?, ?>> createIndexMap(final Text key,
				final Context context) {
			final Map<String, String> map = new HashMap<>();
			map.put("_index", context.getConfiguration().get(INDEX_NAME));
			map.put("_type", context.getConfiguration().get(INDEX_TYPE));
			map.put("_id", key.toString().substring(1, key.getLength() - 1));
			final Map<String, Map<?, ?>> index = new HashMap<>();
			index.put("index", map);
			return index;
		}
	}

	@Override
	public Configuration getConf() {
		return conf;
	}

	@Override
	public void setConf(Configuration conf) {
		this.conf = conf;
	}
}
