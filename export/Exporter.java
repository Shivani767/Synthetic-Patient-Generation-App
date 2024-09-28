package org.mitre.synthea.export;

import ca.uhn.fhir.parser.IParser;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Predicate;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;

import org.mitre.synthea.engine.Generator;
import org.mitre.synthea.export.rif.BB2RIFExporter;
import org.mitre.synthea.helpers.Config;
import org.mitre.synthea.helpers.Utilities;
import org.mitre.synthea.identity.Entity;
import org.mitre.synthea.identity.Seed;
import org.mitre.synthea.identity.Variant;
import org.mitre.synthea.modules.DeathModule;
import org.mitre.synthea.world.agents.Person;
import org.mitre.synthea.world.concepts.Claim;
import org.mitre.synthea.world.concepts.HealthRecord;
import org.mitre.synthea.world.concepts.HealthRecord.Encounter;
import org.mitre.synthea.world.concepts.HealthRecord.Observation;
import org.mitre.synthea.world.concepts.HealthRecord.Report;

public abstract class Exporter {

  /**
   * Supported FHIR versions.
   */
  public enum SupportedFhirVersion {
    DSTU2,
    STU3,
    R4
  }

  private static final List<Pair<Person, Long>> deferredExports =
          Collections.synchronizedList(new LinkedList<>());

  private static final ConcurrentHashMap<Path, PrintWriter> fileWriters =
          new ConcurrentHashMap<Path, PrintWriter>();

  private static final int FILE_BUFFER_SIZE = 4 * 1024 * 1024;

  /**
   * Runtime configuration of the record exporter.
   */
  public static class ExporterRuntimeOptions {

    public int yearsOfHistory;
    public boolean deferExports = false;
    public boolean terminologyService =
        !Config.get("generate.terminology_service_url", "").isEmpty();
    private BlockingQueue<String> recordQueue;
    private SupportedFhirVersion fhirVersion;

    public ExporterRuntimeOptions() {
      yearsOfHistory = Integer.parseInt(Config.get("exporter.years_of_history"));
    }

    /**
     * Copy constructor.
     */
    public ExporterRuntimeOptions(ExporterRuntimeOptions init) {
      yearsOfHistory = init.yearsOfHistory;
      deferExports = init.deferExports;
      terminologyService = init.terminologyService;
      recordQueue = init.recordQueue;
      fhirVersion = init.fhirVersion;
    }

    /**
     * Enables a blocking queue to which FHIR patient records will be written.
     * @param version specifies the version of FHIR that will be written to the queue.
     */
    public void enableQueue(SupportedFhirVersion version) {
      recordQueue = new LinkedBlockingQueue<>(1);
      fhirVersion = version;
    }

    public SupportedFhirVersion queuedFhirVersion() {
      return fhirVersion;
    }

    public boolean isQueueEnabled() {
      return recordQueue != null;
    }

    /**
     * Returns the newest generated patient record
     * or blocks until next record becomes available.
     * Returns null if the generator does not have a record queue.
     */
    public String getNextRecord() throws InterruptedException {
      if (recordQueue == null) {
        return null;
      }
      return recordQueue.take();
    }

    /**
     * Returns true if record queue is empty or null. Otherwise returns false.
     */
    public boolean isRecordQueueEmpty() {
      return recordQueue == null || recordQueue.size() == 0;
    }
  }

  /**
   * Export a single patient, into all the formats supported. (Formats may be enabled or disabled by
   * configuration)
   *
   * @param person   Patient to export
   * @param stopTime Time at which the simulation stopped
   * @param options Runtime exporter options
   */
  public static boolean export(Person person, long stopTime, ExporterRuntimeOptions options) {
    boolean wasExported = false;
    if (options.deferExports) {
      wasExported = true;
      deferredExports.add(new ImmutablePair<Person, Long>(person, stopTime));
    } else {
      if (options.yearsOfHistory > 0) {
        person = filterForExport(person, options.yearsOfHistory, stopTime);
      }
      if (!person.alive(stopTime)) {
        filterAfterDeath(person);
      }
      if (person.hasMultipleRecords) {
        int i = 0;
        for (String key : person.records.keySet()) {
          person.record = person.records.get(key);
          if (person.attributes.get(Person.ENTITY) != null) {
            Entity entity = (Entity) person.attributes.get(Person.ENTITY);
            Seed seed = entity.seedAt(person.record.lastEncounterTime());
            Variant variant = seed.selectVariant(person);
            person.attributes.putAll(variant.demographicAttributesForPerson());
          }
          boolean exported = exportRecord(person, Integer.toString(i), stopTime, options);
          wasExported = wasExported || exported;
          i++;
        }
      } else {
        wasExported = exportRecord(person, "", stopTime, options);
      }
    }
    return wasExported;
  }

  /**
   * Export a single patient, into all the formats supported. (Formats may be enabled or disabled by
   * configuration). This method variant is only currently used by test classes.
   *
   * @param person   Patient to export
   * @param stopTime Time at which the simulation stopped
   */
  public static void export(Person person, long stopTime) {
    export(person, stopTime, new ExporterRuntimeOptions());
  }

  /**
   * Export a single patient record, into all the formats supported.
   * (Formats may be enabled or disabled by configuration)
   *
   * @param person   Patient to export, with Patient.record being set.
   * @param fileTag  An identifier to tag the file with.
   * @param stopTime Time at which the simulation stopped
   * @param options Generator's record queue (may be null)
   */
  private static boolean exportRecord(Person person, String fileTag, long stopTime,
          ExporterRuntimeOptions options) {
    boolean wasExported = true;
    if (options.terminologyService) {
      // Resolve any coded values within the record that are specified using a ValueSet URI.
      ValueSetCodeResolver valueSetCodeResolver = new ValueSetCodeResolver(person);
      valueSetCodeResolver.resolve();
    }

    if (Config.getAsBoolean("exporter.fhir_stu3.export")) {
      File outDirectory = getOutputFolder("fhir_stu3", person);
      if (Config.getAsBoolean("exporter.fhir.bulk_data")) {
        org.hl7.fhir.dstu3.model.Bundle bundle = FhirStu3.convertToFHIR(person, stopTime);
        IParser parser = FhirStu3.getContext().newJsonParser().setPrettyPrint(false);
        for (org.hl7.fhir.dstu3.model.Bundle.BundleEntryComponent entry : bundle.getEntry()) {
          String filename = entry.getResource().getResourceType().toString() + ".ndjson";
          Path outFilePath = outDirectory.toPath().resolve(filename);
          String entryJson = parser.encodeResourceToString(entry.getResource());
          appendToFile(outFilePath, entryJson);
        }
      } else {
        String bundleJson = FhirStu3.convertToFHIRJson(person, stopTime);
        Path outFilePath = outDirectory.toPath().resolve(filename(person, fileTag, "json"));
        writeNewFile(outFilePath, bundleJson);
      }
    }
    if (Config.getAsBoolean("exporter.fhir_dstu2.export")) {
      File outDirectory = getOutputFolder("fhir_dstu2", person);
      if (Config.getAsBoolean("exporter.fhir.bulk_data")) {
        ca.uhn.fhir.model.dstu2.resource.Bundle bundle = FhirDstu2.convertToFHIR(person, stopTime);
        IParser parser = FhirDstu2.getContext().newJsonParser().setPrettyPrint(false);
        for (ca.uhn.fhir.model.dstu2.resource.Bundle.Entry entry : bundle.getEntry()) {
          String filename = entry.getResource().getResourceName() + ".ndjson";
          Path outFilePath = outDirectory.toPath().resolve(filename);
          String entryJson = parser.encodeResourceToString(entry.getResource());
          appendToFile(outFilePath, entryJson);
        }
      } else {
        String bundleJson = FhirDstu2.convertToFHIRJson(person, stopTime);
        Path outFilePath = outDirectory.toPath().resolve(filename(person, fileTag, "json"));
        writeNewFile(outFilePath, bundleJson);
      }
    }
    if (Config.getAsBoolean("exporter.fhir.export")) {
      File outDirectory = getOutputFolder("fhir", person);
      if (Config.getAsBoolean("exporter.fhir.bulk_data")) {
        org.hl7.fhir.r4.model.Bundle bundle = FhirR4.convertToFHIR(person, stopTime);
        IParser parser = FhirR4.getContext().newJsonParser().setPrettyPrint(false);
        for (org.hl7.fhir.r4.model.Bundle.BundleEntryComponent entry : bundle.getEntry()) {
          String filename = entry.getResource().getResourceType().toString() + ".ndjson";
          Path outFilePath = outDirectory.toPath().resolve(filename);
          String entryJson = parser.encodeResourceToString(entry.getResource());
          appendToFile(outFilePath, entryJson);
        }
      } else {
        String bundleJson = FhirR4.convertToFHIRJson(person, stopTime);
        Path outFilePath = outDirectory.toPath().resolve(filename(person, fileTag, "json"));
        writeNewFile(outFilePath, bundleJson);
      }
      FhirGroupExporterR4.addPatient((String) person.attributes.get(Person.ID));
    }
    if (Config.getAsBoolean("exporter.ccda.export")) {
      String ccdaXml = CCDAExporter.export(person, stopTime);
      File outDirectory = getOutputFolder("ccda", person);
      Path outFilePath = outDirectory.toPath().resolve(filename(person, fileTag, "xml"));
      writeNewFile(outFilePath, ccdaXml);
    }
    if (Config.getAsBoolean("exporter.json.export")) {
      String json = JSONExporter.export(person);
      File outDirectory = getOutputFolder("json", person);
      Path outFilePath = outDirectory.toPath().resolve(filename(person, fileTag, "json"));
      writeNewFile(outFilePath, json);
    }
    if (Config.getAsBoolean("exporter.csv.export")) {
      try {
        CSVExporter.getInstance().export(person, stopTime);
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
    if (Config.getAsBoolean("exporter.bfd.export")) {
      try {
        BB2RIFExporter exporter = BB2RIFExporter.getInstance();
        wasExported = exporter.export(person, stopTime, options.yearsOfHistory);
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
    if (Config.getAsBoolean("exporter.cpcds.export")) {
      try {
        CPCDSExporter.getInstance().export(person, stopTime);
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
    if (Config.getAsBoolean("exporter.text.export")) {
      try {
        TextExporter.exportAll(person, fileTag, stopTime);
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
    if (Config.getAsBoolean("exporter.text.per_encounter_export")) {
      try {
        TextExporter.exportEncounter(person, stopTime);
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
    if (Config.getAsBoolean("exporter.symptoms.csv.export")) {
      try {
        SymptomCSVExporter.getInstance().export(person, stopTime);
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
    if (Config.getAsBoolean("exporter.symptoms.text.export")) {
      try {
        SymptomTextExporter.exportAll(person, fileTag, stopTime);
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
    if (Config.getAsBoolean("exporter.cdw.export")) {
      try {
        CDWExporter.getInstance().export(person, stopTime);
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
    if (Config.getAsBoolean("exporter.clinical_note.export")) {
      File outDirectory = getOutputFolder("notes", person);
      Path outFilePath = outDirectory.toPath().resolve(filename(person, fileTag, "txt"));
      String consolidatedNotes = ClinicalNoteExporter.export(person);
      writeNewFile(outFilePath, consolidatedNotes);
    }
    if (options.isQueueEnabled()) {
      try {
        switch (options.queuedFhirVersion()) {
          case DSTU2:
            options.recordQueue.put(FhirDstu2.convertToFHIRJson(person, stopTime));
            break;
          case STU3:
            options.recordQueue.put(FhirStu3.convertToFHIRJson(person, stopTime));
            break;
          default:
            options.recordQueue.put(FhirR4.convertToFHIRJson(person, stopTime));
            break;
        }
      } catch (InterruptedException ie) {
        // ignore
      } catch (Exception e) {
        e.printStackTrace();
      }
    }
    return wasExported;
  }

  /**
   * Write a new file with the given contents. Fails if the file already exists.
   * @param file Path to the new file.
   * @param contents The contents of the file.
   */
  private static void writeNewFile(Path file, String contents) {
    try {
      Files.write(file, Collections.singleton(contents), StandardOpenOption.CREATE_NEW);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  /**
   * Overwrite a file with the given contents. If the file doesn't exist it will be created.
   * @param file Path to the new file.
   * @param contents The contents of the file.
   */
  public static void overwriteFile(Path file, String contents) {
    try {
      Files.write(file, Collections.singleton(contents), StandardOpenOption.CREATE,
              StandardOpenOption.TRUNCATE_EXISTING);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  /**
   * Append contents to the end of a file.
   * @param file Path to the new file.
   * @param contents The contents of the file.
   */
  public static void appendToFile(Path file, String contents) {
    PrintWriter writer = fileWriters.get(file);

    if (writer == null) {
      synchronized (fileWriters) {
        writer = fileWriters.get(file);
        if (writer == null) {
          try {
            writer = new PrintWriter(
              new BufferedWriter(new FileWriter(file.toFile(), true), FILE_BUFFER_SIZE)
            );
          } catch (IOException e) {
            e.printStackTrace();
          }
          fileWriters.put(file, writer);
        }
      }
    }

    synchronized (writer) {
      writer.println(contents);
    }
  }

  /**
   * Flushes the data and closes all open files.
   */
  private static void closeOpenFiles() {
    Iterator<PrintWriter> itr = fileWriters.values().iterator();
    while (itr.hasNext()) {
      itr.next().close();
    }
    fileWriters.clear();
  }

  /**
   * Run any exporters that require the full dataset to be generated prior to exporting.
   * (E.g., an aggregate statistical exporter)
   *
   * @param generator Generator that generated the patients
   */
  public static void runPostCompletionExports(Generator generator) {
    runPostCompletionExports(generator, new ExporterRuntimeOptions());
  }

  /**
   * Run any exporters that require the full dataset to be generated prior to exporting.
   * (E.g., an aggregate statistical exporter)
   *
   * @param generator Generator that generated the patients
   */
  public static void runPostCompletionExports(Generator generator, ExporterRuntimeOptions options) {

    if (options.deferExports) {
      ExporterRuntimeOptions nonDeferredOptions = new ExporterRuntimeOptions(options);
      nonDeferredOptions.deferExports = false;
      for (Pair<Person, Long> entry: deferredExports) {
        export(entry.getLeft(), entry.getRight(), nonDeferredOptions);
      }
      deferredExports.clear();
    }

    try {
      FhirGroupExporterR4.exportAndSave(generator.getRandomizer(), generator.stop);
    } catch (Exception e) {
      e.printStackTrace();
    }

    try {
      HospitalExporterR4.export(generator.getRandomizer(), generator.stop);
    } catch (Exception e) {
      e.printStackTrace();
    }

    try {
      FhirPractitionerExporterR4.export(generator.getRandomizer(), generator.stop);
    } catch (Exception e) {
      e.printStackTrace();
    }

    try {
      HospitalExporterStu3.export(generator.stop);
    } catch (Exception e) {
      e.printStackTrace();
    }

    try {
      FhirPractitionerExporterStu3.export(generator.stop);
    } catch (Exception e) {
      e.printStackTrace();
    }

    try {
      HospitalExporterDstu2.export(generator.stop);
    } catch (Exception e) {
      e.printStackTrace();
    }

    try {
      FhirPractitionerExporterDstu2.export(generator.stop);
    } catch (Exception e) {
      e.printStackTrace();
    }

    if (Config.getAsBoolean("exporter.bfd.export")) {
      try {
        BB2RIFExporter exporter = BB2RIFExporter.getInstance();
        exporter.exportNPIs();
        exporter.exportManifest();
        exporter.exportEndState();
        exporter.exportMissingCodes();
      } catch (IOException e) {
        e.printStackTrace();
      }
    }

    if (Config.getAsBoolean("exporter.cdw.export")) {
      CDWExporter.getInstance().writeFactTables();
    }

    if (Config.getAsBoolean("exporter.csv.export")) {
      try {
        CSVExporter.getInstance().exportOrganizationsAndProviders();
        CSVExporter.getInstance().exportPayers();
      } catch (IOException e) {
        e.printStackTrace();
      }
    }

    if (Config.getAsBoolean("exporter.metadata.export", false)) {
      try {
        MetadataExporter.exportMetadata(generator);
      } catch (IOException e) {
        e.printStackTrace();
      }
    }

    closeOpenFiles();
  }

  /**
   * Filter the patient's history to only the last __ years
   * but also include relevant history from before that. Exclude
   * any history that occurs after the specified end_time -- typically
   * this is the current time/System.currentTimeMillis().
   *
   * @param original    The Person to filter.
   * @param yearsToKeep The last __ years to keep.
   * @param endTime     The time the history ends.
   * @return Modified Person with history expunged.
   */
  public static Person filterForExport(Person original, int yearsToKeep, long endTime) {
    // TODO: clone the patient so that we export only the last _ years
    // but the rest still exists, just in case
    Person filtered = original; //.clone();
    //filtered.record = original.record.clone();

    if (filtered.hasMultipleRecords) {
      for (String key : filtered.records.keySet()) {
        HealthRecord record = filtered.records.get(key);
        filterForExport(record, yearsToKeep, endTime);
      }
    } else {
      filtered.record = filterForExport(filtered.record, yearsToKeep, endTime);
    }

    return filtered;
  }

  /**
   * Filter the health record to only the last __ years
   * but also include relevant history from before that. Exclude
   * any history that occurs after the specified end_time -- typically
   * this is the current time/System.currentTimeMillis().
   *
   * @param record    The record to filter.
   * @param yearsToKeep The last __ years to keep.
   * @param endTime     The time the history ends.
   * @return Modified record with history expunged.
   */
  private static HealthRecord filterForExport(HealthRecord record, int yearsToKeep, long endTime) {

    long cutoffDate = endTime - Utilities.convertTime("years", yearsToKeep);
    Predicate<HealthRecord.Entry> notFutureDated = e -> e.start <= endTime;

    for (Encounter encounter : record.encounters) {
      List<Claim.ClaimEntry> claimItems = encounter.claim.items;
      // keep conditions if still active, regardless of start date
      Predicate<HealthRecord.Entry> conditionActive = c -> record.conditionActive(c.type);
      // or if the condition was active at any point since the cutoff date
      Predicate<HealthRecord.Entry> activeWithinCutoff = c -> c.stop != 0L && c.stop > cutoffDate;
      Predicate<HealthRecord.Entry> keepCondition = conditionActive.or(activeWithinCutoff);
      filterEntries(encounter.conditions, claimItems, cutoffDate, endTime, keepCondition);

      // allergies are essentially the same as conditions
      // But we need to redefine all of the predicates, because we are talking about Allergies as
      // opposed to Entries... You would think that it would work... but generics are hard
      Predicate<HealthRecord.Allergy> allergyActive = c -> record.allergyActive(c.type);
      // or if the condition was active at any point since the cutoff date
      Predicate<HealthRecord.Allergy> allergyActiveWithinCutoff =
          c -> c.stop != 0L && c.stop > cutoffDate;
      Predicate<HealthRecord.Allergy> keepAllergy = allergyActive.or(allergyActiveWithinCutoff);
      filterEntries(encounter.allergies, claimItems, cutoffDate, endTime, keepAllergy);

      // some of the "future death" logic could potentially add a future-dated death certificate
      Predicate<Observation> isCauseOfDeath =
          o -> DeathModule.CAUSE_OF_DEATH_CODE.code.equals(o.type);
      // keep cause of death unless it's future dated
      Predicate<Observation> keepObservation = isCauseOfDeath.and(notFutureDated);
      filterEntries(encounter.observations, claimItems, cutoffDate, endTime, keepObservation);

      // keep all death certificates, unless they are future-dated
      Predicate<Report> isDeathCertificate = r -> DeathModule.DEATH_CERTIFICATE.code.equals(r.type);
      Predicate<Report> keepReport = isDeathCertificate.and(notFutureDated);
      filterEntries(encounter.reports, claimItems, cutoffDate, endTime, keepReport);

      filterEntries(encounter.procedures, claimItems, cutoffDate, endTime, null);

      // keep medications if still active, regardless of start date
      filterEntries(encounter.medications, claimItems, cutoffDate, endTime,
          med -> record.medicationActive(med.type));

      filterEntries(encounter.immunizations, claimItems, cutoffDate, endTime, null);

      // keep careplans if they are still active, regardless of start date
      filterEntries(encounter.careplans, claimItems, cutoffDate, endTime,
          cp -> record.careplanActive(cp.type));
    }

    // if ANY of these are not empty, the encounter is not empty
    Predicate<Encounter> encounterNotEmpty = e ->
        !e.conditions.isEmpty() || !e.allergies.isEmpty()
            || !e.observations.isEmpty() || !e.reports.isEmpty()
            || !e.procedures.isEmpty() || !e.medications.isEmpty()
            || !e.immunizations.isEmpty() || !e.careplans.isEmpty();

    Predicate<Encounter> isDeathCertification =
        e -> !e.codes.isEmpty() && DeathModule.DEATH_CERTIFICATION.equals(e.codes.get(0));
    Predicate<Encounter> keepEncounter =
        encounterNotEmpty.or(isDeathCertification.and(notFutureDated));

    // finally filter out any empty encounters
    filterEntries(record.encounters, Collections.emptyList(), cutoffDate, endTime, keepEncounter);

    return record;
  }

  /**
   * Helper function to filter entries from a list. Entries are kept if their date range falls
   * within the provided range or if `keepFunction` is provided, and returns `true` for the given
   * entry.
   *
   * @param entries      List of `Entry`s to filter
   * @param claimItems   List of ClaimItems, from which any removed items should also be removed.
   * @param cutoffDate   Minimum date, entries older than this may be discarded
   * @param endTime      Maximum date, entries newer than this may be discarded
   * @param keepFunction Keep function, if this function returns `true` for an entry then it will
   *                     be kept
   */
  private static <E extends HealthRecord.Entry> void filterEntries(List<E> entries,
      List<Claim.ClaimEntry> claimItems, long cutoffDate,
      long endTime, Predicate<E> keepFunction) {

    Iterator<E> iterator = entries.iterator();
    // iterator allows us to use the remove() method
    while (iterator.hasNext()) {
      E entry = iterator.next();
      // if the entry is not within the keep time range,
      // and the special keep function (if provided) doesn't say keep it
      // remove it from the list
      if (!entryWithinTimeRange(entry, cutoffDate, endTime)
          && (keepFunction == null || !keepFunction.test(entry))) {
        iterator.remove();

        claimItems.removeIf(ci -> ci.entry == entry);
        // compare with == because we only care if it's the actual same object
      }
    }
  }

  private static boolean entryWithinTimeRange(
      HealthRecord.Entry e, long cutoffDate, long endTime) {

    if (e.start > cutoffDate && e.start <= endTime) {
      return true; // trivial case, when we're within the last __ years
    }

    // if the entry has a stop time, check if the effective date range overlapped the last __ years
    if (e.stop != 0L && e.stop > cutoffDate) {

      if (e.stop > endTime) {
        // If any entries have an end date in the future but are within the cutoffDate,
        // remove the end date but keep the entry (since it's still active).
        e.stop = 0L;
      }

      return true;
    }

    return false;
  }

  /**
   * There is a tiny chance that in the last time step, one module ran to the very end of
   * the time step, and the next killed the person half-way through. In this case,
   * it is possible that an encounter (from the first module) occurred post death.
   * We must filter it out here.
   *
   * @param person The dead person.
   */
  private static void filterAfterDeath(Person person) {
    long deathTime = (long) person.attributes.get(Person.DEATHDATE);
    if (person.hasMultipleRecords) {
      for (HealthRecord record : person.records.values()) {
        Iterator<Encounter> iter = record.encounters.iterator();
        while (iter.hasNext()) {
          Encounter encounter = iter.next();
          if (encounter.start > deathTime
              && !encounter.codes.contains(DeathModule.DEATH_CERTIFICATION)) {
            iter.remove();
          }
        }
      }
    } else {
      Iterator<Encounter> iter = person.record.encounters.iterator();
      while (iter.hasNext()) {
        Encounter encounter = iter.next();
        if (encounter.start > deathTime
            && !encounter.codes.contains(DeathModule.DEATH_CERTIFICATION)) {
          iter.remove();
        }
      }
    }
  }

  /**
   * Get the folder where the patient record should be stored.
   * See the configuration settings "exporter.subfolders_by_id_substring" and
   * "exporter.baseDirectory".
   *
   * @param folderName The base folder to use.
   * @param person     The person being exported.
   * @return Either the base folder provided, or a subdirectory, depending on configuration
   *     settings.
   */
  public static File getOutputFolder(String folderName, Person person) {
    List<String> folders = new ArrayList<>();

    folders.add(folderName);

    if (person != null
        && Config.getAsBoolean("exporter.subfolders_by_id_substring")) {
      String id = (String) person.attributes.get(Person.ID);

      folders.add(id.substring(0, 2));
      folders.add(id.substring(0, 3));
    }

    String baseDirectory = Config.get("exporter.baseDirectory");

    File f = Paths.get(baseDirectory, folders.toArray(new String[0])).toFile();
    f.mkdirs();

    return f;
  }

  /**
   * Get the filename to used to export the patient record.
   * See the configuration setting "exporter.use_uuid_filenames".
   *
   * @param person    The person being exported.
   * @param tag       A tag to add to the filename before the extension.
   * @param extension The file extension to use.
   * @return The filename only (not a path).
   */
  public static String filename(Person person, String tag, String extension) {
    if (Config.getAsBoolean("exporter.use_uuid_filenames")) {
      return person.attributes.get(Person.ID) + tag + "." + extension;
    } else {
      // ensure unique filenames for now
      return person.attributes.get(Person.NAME).toString().replace(' ', '_') + "_"
          + person.attributes.get(Person.ID) + tag + "." + extension;
    }
  }
}
