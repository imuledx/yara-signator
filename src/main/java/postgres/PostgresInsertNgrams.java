package postgres;

import java.io.File;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.checkerframework.common.reflection.qual.GetMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonSyntaxException;

import converters.ConverterFactory;
import converters.linearization.Linearization;
import converters.ngrams.Ngram;
import json.Generator;
import main.Config;
import main.WildcardConfig;
import prefiltering.PrefilterFacade;
import smtx_handler.Instruction;
import smtx_handler.Meta;
import smtx_handler.SMDA;

public class PostgresInsertNgrams implements Runnable {
	
	private static final Logger logger = LoggerFactory.getLogger(PostgresInsertNgrams.class);
	
	public PostgresInsertNgrams(Config config, File[] allSmdaFiles, long minInstructions, boolean firstInsertion,
			boolean atLeastOneElementInNgramCollection, int i) {
		this.config=config;
		this.i=i;
		this.firstInsertion = firstInsertion;
		this.allSmdaFiles = allSmdaFiles;
		this.atLeastOneElementInNgramCollection = atLeastOneElementInNgramCollection;
		this.minInstructions = minInstructions;
	}
	
	private Config config;
	private File[] allSmdaFiles;
	private long minInstructions;
	private boolean firstInsertion;
	private boolean atLeastOneElementInNgramCollection;
	private int i;
	
	public void insertSmdaElement(Config config, File[] allSmdaFiles, long minInstructions, boolean firstInsertion,
			boolean atLeastOneElementInNgramCollection, int i) throws IllegalStateException, SQLException {
		
		SMDA smda = null;
		Meta metadata = null;
		
		try {
			smda = new Generator().generateSMDA(allSmdaFiles[i].getAbsolutePath());
			
			String family, filename;

			if(smda == null) {
				logger.error("NO SMDA object retrieved. Faulty smda report!" + allSmdaFiles[i].getAbsolutePath());
				logger.error("smda: " + allSmdaFiles[i].getAbsolutePath());
				return;
			}
			
			if(smda.getMetadata() == null) {
				logger.error("No MetaData found. Faulty smda report! " + allSmdaFiles[i].getAbsolutePath());
				logger.error("smda: " + smda.toString());
				return;
			}
			
			logger.info(smda.getMetadata().toString());
			
			if(smda.getMetadata().getFamily() != null) {
				family = smda.getMetadata().getFamily();
			} else {
				logger.error("No family name found. Faulty smda report! " + allSmdaFiles[i].getAbsolutePath());
				logger.error("smda: " + smda.toString());
				return;
			}
			
			if(smda.getMetadata().getFilename() != null) {
				filename = smda.getMetadata().getFilename();
			} else {
				logger.error("No filename found. Faulty smda report! " + allSmdaFiles[i].getAbsolutePath());
				logger.error("smda: " + smda.toString());
				return;
			}
			
			
		} catch(IllegalStateException | JsonSyntaxException | NullPointerException e) {
			e.printStackTrace();
			logger.debug(e.getLocalizedMessage());
			logger.debug("smda: " + smda.toString());
			return;
		}
		
		/*
		 * Step 0
		 * Sanitize the input:
		 */
		if(smda == null || 
				smda.getMetadata().getFilename() == null || 
				smda.getMetadata().getFilename().isEmpty()) {
			System.out.println("null pointer in smda creation, no valid file");
			
		} else if(smda.getMetadata() == null) {
			System.out.println("CONTINUE: NO SUMMARY DETECTED in " 
					+ smda.getMetadata().getFamily() + " - " 
					+ smda.getMetadata().getFilename() );
			return;
			
		} else if(smda.getXcfg() == null) {
			System.out.println("CONTINUE: NO CFG DETECTED in " 
					+ smda.getMetadata().getFamily() + " - " 
					+ smda.getMetadata().getFilename() );
			return;
			
		} else if(smda.getXcfg().getFunctions() == null) {
			System.out.println("CONTINUE: NO FUNCTIONS DETECTED in " 
					+ smda.getMetadata().getFamily() + " - " 
					+ smda.getMetadata().getFilename() );
			return;
			
		} else if(smda.getStatistics().getNum_instructions() < minInstructions) {
			System.out.println("CONTINUE: NOT ENOUGH INSTRUCTIONS FOUND in " 
					+ smda.getMetadata().getFamily() + " - " 
					+ smda.getMetadata().getFilename() + " - " 
					+ smda.getStatistics().getNum_instructions() + "/" + minInstructions);
			return;
		}
		
		metadata = smda.getMetadata();
		
		/*
		 * Linearize the disassembly.
		 */
		Linearization linearized = new ConverterFactory().getLinearized(smda);
		
		
		/*
		 * Build the n-grams for each n:
		 */
		ArrayList<Integer> allN = config.getNs();
		List<Ngram> ngrams = null;
		
		int family_id = writeFamilyToDatabase(smda);
		
		
		int sample_id = writeSampleToDatabase(smda, family_id);
		
		
		
		if(sample_id == 0) {
			System.out.println("Error: sample_id should never be zero!");
		}
		
		// Debugging the count of ngrams for various n
		Map<Integer, Integer> counter = new HashMap<Integer, Integer>();
		
		//System.out.println("start n");
		for(int n : allN) {
			
			/*
			 * ACTIVATE ONLY IF YOU WANT A PARTITIONED NGRAM_4 TABLE.
			 */
			
			//createPartitionedNgramTable(family_id, n);
			
			ngrams = new ConverterFactory().calculateNgrams("createWithoutOverlappingCodeCaves", linearized, n);
			
			if(config.wildcardConfigEnabled) {
				PrefilterFacade pre = new PrefilterFacade();
				logger.debug("running the prefilter engine");
				ngrams = pre.prefilterAction(ngrams, config.getWildcardConfigConfig());
			}
			
			if(!config.duplicatesInsideSamplesEnabled) {
				int sizeBefore = ngrams.size();
				HashSet<Ngram> s = new HashSet<>();
				s.addAll(ngrams);
				ngrams = new ArrayList<>(s);
				int sizeAfter = ngrams.size();
				logger.debug(metadata.getFamily() + " - " + metadata.getFilename() + " - size_before: " + sizeBefore + " - size_after: " + sizeAfter);
			}

			if(ngrams.isEmpty()) {
				System.out.println("no ngrams detected, got null in " + metadata.getFilename());
				continue;
			}
			logger.trace("Writing ngrams_" + n + " (size: " + ngrams.size() + ") for " 
					+ metadata.getFamily() + " - " + metadata.getFilename() + " into db.");
			
			counter.put(n, ngrams.size());
			writeNgramsToDatabase(smda, ngrams, n, config.batchSize, sample_id, family_id);
		}
		
		logger.info("[INSERTION_STEP] Progress: " 
				+ (int)(( (float) (i + 1) / allSmdaFiles.length)*100.0) + "% - " 
				+ "Step: " + (i + 1) + "/" + allSmdaFiles.length +
				" - Sample: " + metadata.getFamily() +
				" " + metadata.getFilename() + " " + smda.getArchitecture() + " " + smda.getBitness());
		logger.info("Ngram stats: " + counter.toString());
	}

	private int writeSampleToDatabase(SMDA smda, int family_id) throws SQLException {
		PreparedStatement pst = PostgresConnection.INSTANCE.psql_connection.prepareStatement("INSERT INTO samples ("
				+ "family_id , architecture , base_addr , status"
				+ ", num_api_calls , num_basic_blocks , num_disassembly_errors , num_function_calls , num_functions"
				+ ", num_instructions , num_leaf_functions , num_recursive_functions ,"
				+ "timestamp , hash , filename, bitness, binary_size) VALUES (?,?,?,?"
				+ ",?,?,?,?,?"
				+ ",?,?,?"
				+ ",?,?,?,?,?) ON CONFLICT DO NOTHING RETURNING id;", Statement.RETURN_GENERATED_KEYS);
				
		//pst.setString(1, smda.getFamily());
		pst.setInt(1, family_id);
		pst.setString(2, smda.getArchitecture());
		pst.setLong(3, smda.getBase_addr());
		pst.setString(4, smda.getStatus());
		
		pst.setLong(5, smda.getStatistics().getNum_api_calls());
		pst.setLong(6, smda.getStatistics().getNum_basic_blocks());
		
		/*
		 * We are currently only interested in the disassembly failed functions,
		 * although interested is not really the right word, we just save them.
		 */
		
		pst.setLong(7, smda.getStatistics().getNum_failed_functions());
		
		pst.setLong(8, smda.getStatistics().getNum_function_calls());
		pst.setLong(9, smda.getStatistics().getNum_functions());
		pst.setLong(10, smda.getStatistics().getNum_instructions());
		pst.setLong(11, smda.getStatistics().getNum_leaf_functions());
		pst.setLong(12, smda.getStatistics().getNum_recursive_functions());
		
		pst.setString(13, smda.getTimestamp());
		pst.setString(14, smda.getSha256());
		pst.setString(15, smda.getMetadata().getFilename());
		pst.setLong(16, smda.getBitness());
		pst.setLong(17, smda.getBinary_size());
		//pst.setString(18, smda.getMetaData().getMalpedia_filepath());
		
		int rowsModified = pst.executeUpdate();
		
		ResultSet rs = pst.getGeneratedKeys();
		int returnID = 0;
		if(rs.next()) {
			returnID = (int) rs.getObject(1);
		} else {
			throw new SQLException("no return ID could be found for " 
					+ smda.getMetadata().getFamily() + " - " 
					+ smda.getMetadata().getFilename());
		}
		//PostgresConnection.INSTANCE.psql_connection.commit();
		
		return returnID;
	}

	private int writeFamilyToDatabase(SMDA smda) throws SQLException {
		PreparedStatement pst = PostgresConnection.INSTANCE.psql_connection.prepareStatement("INSERT INTO families (family) VALUES (?) ON CONFLICT DO NOTHING RETURNING id;", Statement.RETURN_GENERATED_KEYS);
		pst.setString(1, smda.getMetadata().getFamily());
		
		int rowsModified = pst.executeUpdate();
		
		ResultSet rs = pst.getGeneratedKeys();
		int returnID = 0;
		if(rs.next()) {
			returnID = (int) rs.getObject(1);
		} else {
			pst = PostgresConnection.INSTANCE.psql_connection.prepareStatement("SELECT * FROM families WHERE family = ?");
			pst.setString(1, smda.getMetadata().getFamily());
			pst.execute();
			rs = pst.getResultSet();
			if(rs.next()) {
				returnID = rs.getInt("id");
				System.out.println("id for family " + smda.getMetadata().getFamily() + " is " + returnID);
			} else {
				throw new SQLException("no return ID could be found for " 
						+ smda.getMetadata().getFamily() + " - " 
						+ smda.getMetadata().getFilename());
			}
		}
		if(returnID == 0) {
			throw new SQLException("no return ID could be found for " 
					+ smda.getMetadata().getFamily() + " - " 
					+ smda.getMetadata().getFilename());
		}
		
		return returnID;
	}

	private void writeNgramsToDatabase(SMDA smda, List<Ngram> ngrams, int n, int batchSize, int sample_id, int family_id) throws SQLException {
		
		/*
		 * Table Design:
		 * 
		 * 	

		 	private final String createNgramTable = "CREATE TABLE IF NOT EXISTS ngrams ("
			+ "score SMALLINT,"
			+ "sample_id INTEGER NOT NULL REFERENCES samples(id) ON DELETE CASCADE,"
			+ "family_id INTEGER NOT NULL REFERENCES samples(family_id) ON DELETE CASCADE,"
			+ "concat TEXT NOT NULL,"
			+ "addr_offset BIGINT NOT NULL"
			+ ") PARTITION BY LIST(family_id);";

		 * 
		 */
		
		
		String insertIntoNgrams = "INSERT INTO "			//					ARRAY		ARRAY			ARRAY
				+ "ngrams (concat, sample_id, family_id, score) "
				+ "VALUES (?,?,?,?)";

		insertIntoNgrams = insertIntoNgrams.replace("ngrams", "ngrams_" + n + "_part");
		
		//PostgresConnection.INSTANCE.psql_connection.setAutoCommit(false);
		PreparedStatement pstIntoNgramsTable = PostgresConnection.INSTANCE.psql_connection.prepareStatement(insertIntoNgrams);
		
		int batchCounter=0;
		for(Ngram ngram: ngrams) {
			batchCounter++;
			
			StringBuilder concat = new StringBuilder();
			
			for(Instruction i: ngram.getNgramInstructions()) {
				concat.append("#");
				concat.append(i.getOpcodes());
			}
			
			pstIntoNgramsTable.setString(1, concat.toString());
			pstIntoNgramsTable.setInt(2, sample_id);
			pstIntoNgramsTable.setInt(3, family_id);
			pstIntoNgramsTable.setShort(4, (short)0);
			
			try {
				pstIntoNgramsTable.addBatch();
			} catch(SQLException e) {
				e.printStackTrace();
			}
			
			if(batchCounter%30000 == 0) {
				pstIntoNgramsTable.executeBatch();
				PostgresConnection.INSTANCE.psql_connection.commit();
			}
			
		}
		
		pstIntoNgramsTable.executeBatch();
		PostgresConnection.INSTANCE.psql_connection.commit();
	}

	@Override
	public void run() {
		try {
			insertSmdaElement(config, allSmdaFiles, minInstructions, firstInsertion,
					atLeastOneElementInNgramCollection, i);
		} catch (IllegalStateException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			System.out.println("We have an illegal state exception in the file: " + allSmdaFiles[i].getAbsolutePath());
			System.out.println("Further information could not be retrieved because the file was unparseable for us.");
		} catch (Exception e) {
			e.printStackTrace();
			System.out.println("We have an illegal state exception in the file: " + allSmdaFiles[i].getAbsolutePath());
			System.out.println("Further information could not be retrieved because the file was unparseable for us.");
		}
	}

}
