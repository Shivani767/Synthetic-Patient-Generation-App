package org.mitre.synthea.export;

import ca.uhn.fhir.model.api.ExtensionDt;
import ca.uhn.fhir.model.dstu2.resource.Bundle;
import ca.uhn.fhir.model.dstu2.resource.Bundle.Entry;
import ca.uhn.fhir.model.dstu2.resource.Organization;
import ca.uhn.fhir.model.dstu2.valueset.BundleTypeEnum;
import ca.uhn.fhir.model.primitive.IntegerDt;
import ca.uhn.fhir.parser.IParser;

import com.google.common.collect.Table;

import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import org.mitre.synthea.helpers.Config;
import org.mitre.synthea.world.agents.Provider;

public abstract class HospitalExporterDstu2 {

  private static final String SYNTHEA_URI = "http://synthetichealth.github.io/synthea/";

  /**
   * Export the hospital in FHIR DSTU2 format.
   */
  public static void export(long stop) {
    if (Config.getAsBoolean("exporter.hospital.fhir_dstu2.export")) {

      Bundle bundle = new Bundle();
      if (Config.getAsBoolean("exporter.fhir.transaction_bundle")) {
        bundle.setType(BundleTypeEnum.BATCH);
      } else {
        bundle.setType(BundleTypeEnum.COLLECTION);
      }
      for (Provider h : Provider.getProviderList()) {
        // filter - exports only those hospitals in use
        Table<Integer, String, AtomicInteger> utilization = h.getUtilization();
        int totalEncounters = utilization.column(Provider.ENCOUNTERS).values().stream()
            .mapToInt(ai -> ai.get()).sum();
        if (totalEncounters > 0) {
          Entry entry = FhirDstu2.provider(bundle, h);
          addHospitalExtensions(h, (Organization) entry.getResource());
        }
      }

      boolean ndjson = Config.getAsBoolean("exporter.fhir.bulk_data", false);
      File outputFolder = Exporter.getOutputFolder("fhir_dstu2", null);
      IParser parser = FhirDstu2.getContext().newJsonParser();

      if (ndjson) {
        Path outFilePath = outputFolder.toPath().resolve("Organization." + stop + ".ndjson");
        for (Bundle.Entry entry : bundle.getEntry()) {
          String entryJson = parser.encodeResourceToString(entry.getResource());
          Exporter.appendToFile(outFilePath, entryJson);
        }
      } else {
        Boolean pretty = Config.getAsBoolean("exporter.pretty_print", true);
        parser = parser.setPrettyPrint(pretty);
        Path outFilePath = outputFolder.toPath().resolve("hospitalInformation" + stop + ".json");
        String bundleJson = parser.encodeResourceToString(bundle);
        Exporter.overwriteFile(outFilePath, bundleJson);
      }
    }
  }

  /**
   * Add FHIR extensions to capture additional information.
   */
  public static void addHospitalExtensions(Provider h, Organization organizationResource) {
    Table<Integer, String, AtomicInteger> utilization = h.getUtilization();
    // calculate totals for utilization
    int totalEncounters = utilization.column(Provider.ENCOUNTERS).values().stream()
        .mapToInt(ai -> ai.get()).sum();
    ExtensionDt encountersExtension = new ExtensionDt();
    encountersExtension.setUrl(SYNTHEA_URI + "utilization-encounters-extension");
    IntegerDt encountersValue = new IntegerDt(totalEncounters);
    encountersExtension.setValue(encountersValue);
    organizationResource.addUndeclaredExtension(encountersExtension);

    int totalProcedures = utilization.column(Provider.PROCEDURES).values().stream()
        .mapToInt(ai -> ai.get()).sum();
    ExtensionDt proceduresExtension = new ExtensionDt();
    proceduresExtension.setUrl(SYNTHEA_URI + "utilization-procedures-extension");
    IntegerDt proceduresValue = new IntegerDt(totalProcedures);
    proceduresExtension.setValue(proceduresValue);
    organizationResource.addUndeclaredExtension(proceduresExtension);

    int totalLabs = utilization.column(Provider.LABS).values().stream().mapToInt(ai -> ai.get())
        .sum();
    ExtensionDt labsExtension = new ExtensionDt();
    labsExtension.setUrl(SYNTHEA_URI + "utilization-labs-extension");
    IntegerDt labsValue = new IntegerDt(totalLabs);
    labsExtension.setValue(labsValue);
    organizationResource.addUndeclaredExtension(labsExtension);

    int totalPrescriptions = utilization.column(Provider.PRESCRIPTIONS).values().stream()
        .mapToInt(ai -> ai.get()).sum();
    ExtensionDt prescriptionsExtension = new ExtensionDt();
    prescriptionsExtension.setUrl(SYNTHEA_URI + "utilization-prescriptions-extension");
    IntegerDt prescriptionsValue = new IntegerDt(totalPrescriptions);
    prescriptionsExtension.setValue(prescriptionsValue);
    organizationResource.addUndeclaredExtension(prescriptionsExtension);

    Integer bedCount = h.getBedCount();
    if (bedCount != null) {
      ExtensionDt bedCountExtension = new ExtensionDt();
      bedCountExtension.setUrl(SYNTHEA_URI + "bed-count-extension");
      IntegerDt bedCountValue = new IntegerDt(bedCount);
      bedCountExtension.setValue(bedCountValue);
      organizationResource.addUndeclaredExtension(bedCountExtension);
    }
  }
}