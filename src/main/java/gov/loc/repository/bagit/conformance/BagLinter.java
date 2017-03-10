package gov.loc.repository.bagit.conformance;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;

import gov.loc.repository.bagit.conformance.profile.BagitProfile;
import gov.loc.repository.bagit.domain.Bag;
import gov.loc.repository.bagit.domain.Version;
import gov.loc.repository.bagit.exceptions.InvalidBagMetadataException;
import gov.loc.repository.bagit.exceptions.InvalidBagitFileFormatException;
import gov.loc.repository.bagit.exceptions.MaliciousPathException;
import gov.loc.repository.bagit.exceptions.UnparsableVersionException;
import gov.loc.repository.bagit.exceptions.UnsupportedAlgorithmException;
import gov.loc.repository.bagit.exceptions.conformance.BagitVersionIsNotAcceptableException;
import gov.loc.repository.bagit.exceptions.conformance.FetchFileNotAllowedException;
import gov.loc.repository.bagit.exceptions.conformance.MetatdataValueIsNotAcceptableException;
import gov.loc.repository.bagit.exceptions.conformance.RequiredManifestNotPresentException;
import gov.loc.repository.bagit.exceptions.conformance.RequiredMetadataFieldNotPresentException;
import gov.loc.repository.bagit.exceptions.conformance.RequiredTagFileNotPresentException;
import gov.loc.repository.bagit.reader.BagReader;
import gov.loc.repository.bagit.reader.BagitTextFileReader;
import gov.loc.repository.bagit.reader.KeyValueReader;
import gov.loc.repository.bagit.verify.BagVerifier;

/**
 * Responsible for checking a bag and providing insight into how it cause problems.
 */
public class BagLinter {
  private static final Logger logger = LoggerFactory.getLogger(BagLinter.class);
  private static final Version VERSION_1_0 = new Version(1,0);
  
  private final BagReader reader;
  
  public BagLinter(){
    reader = new BagReader();
  }
  
  /**
   * Check a bag against a bagit-profile as described by 
   * <a href="https://github.com/ruebot/bagit-profiles">https://github.com/ruebot/bagit-profiles</a>
   * <br>Note: <b> This implementation does not check the Serialization part of the profile!</b>
   * 
   * @param jsonProfile the input stream to the json string describing the profile
   * @param bag the bag to check against the profile
   * 
   * @throws IOException if there is a problem reading the profile
   * @throws JsonMappingException if there is a problem mapping the profile to the {@link BagitProfile}
   * @throws JsonParseException if there is a problem parsing the json while mapping to java object
   * 
   * @throws FetchFileNotAllowedException if there is a fetch file when the profile prohibits it
   * @throws MetatdataValueIsNotAcceptableException if a metadata value is not in the list of acceptable values
   * @throws RequiredMetadataFieldNotPresentException if a metadata field is not present but it should be
   * @throws RequiredManifestNotPresentException if a payload or tag manifest type is not present but should be
   * @throws BagitVersionIsNotAcceptableException if the version of the bag is not in the list of acceptable versions
   * @throws RequiredTagFileNotPresentException if a tag file is not present but should be
   */
  public void checkAgainstProfile(final InputStream jsonProfile, final Bag bag) throws JsonParseException, JsonMappingException, 
  IOException, FetchFileNotAllowedException, RequiredMetadataFieldNotPresentException, MetatdataValueIsNotAcceptableException, RequiredManifestNotPresentException, 
  BagitVersionIsNotAcceptableException, RequiredTagFileNotPresentException{
    BagProfileChecker.bagConformsToProfile(jsonProfile, bag);
  }
  
  /**
   * The BagIt specification is very flexible in what it allows which leads to situations 
   * where something may be technically allowed, but should be discouraged.
   * This method checks a bag for potential problems, or other items that are allowed but discouraged.
   * This <strong>does not</strong> validate a bag. See {@link BagVerifier} instead.
   * 
   * @param rootDir the root directory of the bag
   * 
   * @return a set of {@link BagitWarning} detailing all items that should be fixed.
   * 
   * @throws InvalidBagMetadataException if the bag metadata does not conform to the bagit specification
   * @throws UnparsableVersionException if there is an error reading the bagit version
   * @throws IOException if there was an error reading a file
   * @throws UnsupportedAlgorithmException if the {@link BagReader} does not support reading the manifest 
   * @throws MaliciousPathException If the manifest was crafted to try and access a file outside the bag directory
   * @throws InvalidBagitFileFormatException if one or more of the files do not correctly follow the bagit specification format
   */
  public Set<BagitWarning> lintBag(final Path rootDir) throws IOException, UnparsableVersionException, InvalidBagMetadataException, MaliciousPathException, UnsupportedAlgorithmException, InvalidBagitFileFormatException{
    return this.lintBag(rootDir, Collections.emptyList());
  }
  
  /**
   * The BagIt specification is very flexible in what it allows which leads to situations 
   * where something may be technically allowed, but should be discouraged.
   * This method checks a bag for potential problems, or other items that are allowed but discouraged.
   * This <strong>does not</strong> validate a bag. See {@link BagVerifier} instead.
   * 
   * @param rootDir the root directory of the bag
   * @param warningsToIgnore any {@link BagitWarning} to ignore when linting
   * 
   * @return a set of {@link BagitWarning} detailing all items that should be fixed.
   * 
   * @throws InvalidBagMetadataException if the bag metadata does not conform to the bagit specification
   * @throws UnparsableVersionException if there is an error reading the bagit version
   * @throws IOException if there was an error reading a file
   * @throws UnsupportedAlgorithmException if the {@link BagReader} does not support reading the manifest 
   * @throws MaliciousPathException If the manifest was crafted to try and access a file outside the bag directory
   * @throws InvalidBagitFileFormatException if one or more of the files do not correctly follow the bagit specification format
   */
  public Set<BagitWarning> lintBag(final Path rootDir, final Collection<BagitWarning> warningsToIgnore) throws IOException, UnparsableVersionException, InvalidBagMetadataException, MaliciousPathException, UnsupportedAlgorithmException, InvalidBagitFileFormatException{
    final Set<BagitWarning> warnings = new HashSet<>();
    
    //@Incubating
    Path bagitDir = rootDir.resolve(".bagit");
    if(!Files.exists(bagitDir)){
      bagitDir = rootDir;
    }
    
    final Path bagitFile = bagitDir.resolve("bagit.txt");
    checkForExtraLines(bagitFile, warnings, warningsToIgnore);
    final SimpleImmutableEntry<Version, Charset> bagitInfo = BagitTextFileReader.readBagitTextFile(bagitFile);
    
    logger.debug("Checking encoding problems.");
    EncodingChecker.checkEncoding(bagitInfo.getValue(), warnings, warningsToIgnore);
    
    logger.debug("checking for latest version.");
    VersionChecker.checkVersion(bagitInfo.getKey(), warnings, warningsToIgnore);
    
    logger.debug("checking manifests for problems.");
    ManifestChecker.checkManifests(bagitDir, bagitInfo.getValue(), warnings, warningsToIgnore);

    logger.debug("checking bag metadata for problems.");
    MetadataChecker.checkBagMetadata(bagitDir, bagitInfo.getValue(), warnings, warningsToIgnore);
    
    return warnings;
  }
  
  public BagReader getReader() {
    return reader;
  }
  
  private void checkForExtraLines(final Path bagitFile, final Collection<BagitWarning> warnings, final Collection<BagitWarning> warningsToIgnore) throws InvalidBagMetadataException, IOException, UnparsableVersionException{
    if(warningsToIgnore.contains(BagitWarning.EXTRA_LINES_IN_BAGIT_FILES)){
      logger.debug("skipping check for extra lines in bagit files");
      return;
    }
    
    logger.debug("checking if [{}] contains more than 2 lines");
    final List<SimpleImmutableEntry<String, String>> pairs = KeyValueReader.readKeyValuesFromFile(bagitFile, ":", StandardCharsets.UTF_8);
     
    for(final SimpleImmutableEntry<String, String> pair : pairs){
      if("BagIt-Version".equals(pair.getKey())){
        final Version version = BagitTextFileReader.parseVersion(pair.getValue());
        //versions before 1.0 specified it must be exactly 2 lines
        if(pairs.size() > 2 && version.isOlder(VERSION_1_0)){
          logger.warn("The bagit specification states that the bagit.txt file must contain exactly 2 lines. "
              + "However we found {} lines, some implementations will "
              + "ignore this but may cause imcompatibility issues with other tools.", pairs.size());
          warnings.add(BagitWarning.EXTRA_LINES_IN_BAGIT_FILES);
        }
      }
    }
  }
}