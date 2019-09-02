package skadistats.clarity.processor.sendtables;

import skadistats.clarity.decoder.Util;
import skadistats.clarity.decoder.s2.S2UnpackerFactory;
import skadistats.clarity.decoder.s2.Serializer;
import skadistats.clarity.decoder.s2.Serializer2;
import skadistats.clarity.decoder.s2.SerializerId;
import skadistats.clarity.decoder.s2.field.CArrayField;
import skadistats.clarity.decoder.s2.field.Field2;
import skadistats.clarity.decoder.s2.field.FieldProperties;
import skadistats.clarity.decoder.s2.field.FieldType;
import skadistats.clarity.decoder.s2.field.PointerField;
import skadistats.clarity.decoder.s2.field.RecordField;
import skadistats.clarity.decoder.s2.field.UnpackerProperties;
import skadistats.clarity.decoder.s2.field.VArrayField;
import skadistats.clarity.decoder.s2.field.ValueField;
import skadistats.clarity.model.BuildNumberRange;
import skadistats.clarity.wire.s2.proto.S2NetMessages;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;

public class FieldGenerator {

    private final S2NetMessages.CSVCMsg_FlattenedSerializer protoMessage;
    private final FieldData[] fieldData;
    private final List<PatchFunc> patchFuncs;

    private final Map<SerializerId, Serializer2> serializers = new HashMap<>();

    public FieldGenerator(S2NetMessages.CSVCMsg_FlattenedSerializer protoMessage, int buildNumber) {
        this.protoMessage = protoMessage;
        this.fieldData = new FieldData[protoMessage.getFieldsCount()];
        this.patchFuncs = new ArrayList<>();
        for (Map.Entry<BuildNumberRange, PatchFunc> patchEntry : PATCHES.entrySet()) {
            if (patchEntry.getKey().appliesTo(buildNumber)) {
                this.patchFuncs.add(patchEntry.getValue());
            }
        }
    }

    public void run() {
        for (int i = 0; i < fieldData.length; i++) {
            fieldData[i] = generateFieldData(protoMessage.getFields(i));
        }
        for (int i = 0; i < protoMessage.getSerializersCount(); i++) {
            Serializer2 serializer = generateSerializer(protoMessage.getSerializers(i));
            serializers.put(serializer.getId(), serializer);
        }
    }

    private FieldData generateFieldData(S2NetMessages.ProtoFlattenedSerializerField_t proto) {
        FieldData fd = new FieldData();
        fd.fieldType = FieldType.forString(sym(proto.getVarTypeSym()));
        fd.nameFunction = fieldNameFunction(proto);
        fd.unpackerProperties = new UnpackerPropertiesImpl(
                proto.hasEncodeFlags() ? proto.getEncodeFlags() : null,
                proto.hasBitCount() ? proto.getBitCount() : null,
                proto.hasLowValue() ? proto.getLowValue() : null,
                proto.hasHighValue() ? proto.getHighValue() : null,
                proto.hasVarEncoderSym() ? sym(proto.getVarEncoderSym()) : null
        );
        if (proto.hasFieldSerializerNameSym()) {
            fd.serializerId = new SerializerId(
                    sym(proto.getFieldSerializerNameSym()),
                    proto.getFieldSerializerVersion()
            );
        }
        for (PatchFunc patchFunc : patchFuncs) {
            patchFunc.execute(fd);
        }
        return fd;
    }

    private Serializer2 generateSerializer(S2NetMessages.ProtoFlattenedSerializer_t proto) {
        SerializerId sid = new SerializerId(
                sym(proto.getSerializerNameSym()),
                proto.getSerializerVersion()
        );
        Field2[] fields = new Field2[proto.getFieldsIndexCount()];
        for (int i = 0; i < fields.length; i++) {
            int fi = proto.getFieldsIndex(i);
            if (fieldData[fi].field == null) {
                fieldData[fi].field = createField(fieldData[fi]);
            }
            fields[i] = fieldData[fi].field;
        }
        return new Serializer2(sid, fields);
    }

    private Field2 createField(FieldData fd) {
        if (fd.serializerId != null) {
            if (POINTERS.contains(fd.fieldType.getBaseType())) {
                return new PointerField(
                        new FieldPropertiesImpl(fd.fieldType, fd.nameFunction),
                        S2UnpackerFactory.createUnpacker(UnpackerProperties.DEFAULT, "bool"),
                        serializers.get(fd.serializerId)
                );
            } else {
                return new VArrayField(
                        new FieldPropertiesImpl(fd.fieldType, fd.nameFunction),
                        S2UnpackerFactory.createUnpacker(UnpackerProperties.DEFAULT, "uint32"),
                        new RecordField(
                                new FieldPropertiesImpl(fd.fieldType, Util::arrayIdxToString),
                                serializers.get(fd.serializerId)
                        )
                );
            }
        }
        String elementCount = fd.fieldType.getElementCount();
        if (elementCount != null && !"char".equals(fd.fieldType.getBaseType())) {
            Integer countAsInt = ITEM_COUNTS.get(elementCount);
            if (countAsInt == null) {
                countAsInt = Integer.valueOf(elementCount);
            }
            return new CArrayField(
                    new FieldPropertiesImpl(fd.fieldType, fd.nameFunction),
                    new ValueField(
                            new FieldPropertiesImpl(fd.fieldType.getElementType(), Util::arrayIdxToString),
                            S2UnpackerFactory.createUnpacker(fd.unpackerProperties, fd.fieldType.getBaseType())
                    ),
                    countAsInt
            );
        }
        if ("CUtlVector".equals(fd.fieldType.getBaseType())) {
            return new VArrayField(
                    new FieldPropertiesImpl(fd.fieldType, fd.nameFunction),
                    S2UnpackerFactory.createUnpacker(UnpackerProperties.DEFAULT, "uint32"),
                    new ValueField(
                            new FieldPropertiesImpl(fd.fieldType.getGenericType(), Util::arrayIdxToString),
                            S2UnpackerFactory.createUnpacker(fd.unpackerProperties, fd.fieldType.getGenericType().getBaseType())
                    )
            );
        }
        return new ValueField(
                new FieldPropertiesImpl(fd.fieldType, fd.nameFunction),
                S2UnpackerFactory.createUnpacker(fd.unpackerProperties, fd.fieldType.getBaseType())
        );
    }

    private String sym(int i) {
        return protoMessage.getSymbols(i);
    }

    private IntFunction<String> fieldNameFunction(S2NetMessages.ProtoFlattenedSerializerField_t field) {
        String name = sym(field.getVarNameSym());
        return i -> name;
    }

    private static class FieldData {
        private FieldType fieldType;
        private IntFunction<String> nameFunction;
        private UnpackerPropertiesImpl unpackerProperties;
        private SerializerId serializerId;
        private Field2 field;
    }

    public static class FieldPropertiesImpl implements FieldProperties {

        private FieldType fieldType;
        private IntFunction<String> nameFunction;

        public FieldPropertiesImpl(FieldType fieldType, IntFunction<String> nameFunction) {
            this.fieldType = fieldType;
            this.nameFunction = nameFunction;
        }

        @Override
        public FieldType getType() {
            return fieldType;
        }

        @Override
        public String getName(int i) {
            return nameFunction.apply(i);
        }

        @Override
        public Serializer getSerializer() {
            return null;
        }

    }

    public static class UnpackerPropertiesImpl implements UnpackerProperties {

        private Integer encodeFlags;
        private Integer bitCount;
        private Float lowValue;
        private Float highValue;
        private String encoderType;

        public UnpackerPropertiesImpl(Integer encodeFlags, Integer bitCount, Float lowValue, Float highValue, String encoderType) {
            this.encodeFlags = encodeFlags;
            this.bitCount = bitCount;
            this.lowValue = lowValue;
            this.highValue = highValue;
            this.encoderType = encoderType;
        }

        @Override
        public Integer getEncodeFlags() {
            return encodeFlags;
        }

        @Override
        public Integer getBitCount() {
            return bitCount;
        }

        @Override
        public Float getLowValue() {
            return lowValue;
        }

        @Override
        public Float getHighValue() {
            return highValue;
        }

        @Override
        public String getEncoderType() {
            return encoderType;
        }

        @Override
        public int getEncodeFlagsOrDefault(int defaultValue) {
            return encodeFlags != null ? encodeFlags : defaultValue;
        }

        @Override
        public int getBitCountOrDefault(int defaultValue) {
            return bitCount != null ? bitCount : defaultValue;
        }

        @Override
        public float getLowValueOrDefault(float defaultValue) {
            return lowValue != null ? lowValue : defaultValue;
        }

        @Override
        public float getHighValueOrDefault(float defaultValue) {
            return highValue != null ? highValue : defaultValue;
        }
    }

    private static final Set<String> POINTERS = new HashSet<>();
    static {
        POINTERS.add("CBodyComponent");
        POINTERS.add("CEntityIdentity");
        POINTERS.add("CPhysicsComponent");
        POINTERS.add("CRenderComponent");
        POINTERS.add("CDOTAGamerules");
        POINTERS.add("CDOTAGameManager");
        POINTERS.add("CDOTASpectatorGraphManager");
        POINTERS.add("CPlayerLocalData");
    }

    private static final Map<String, Integer> ITEM_COUNTS = new HashMap<>();
    static {
        ITEM_COUNTS.put("MAX_ITEM_STOCKS", 8);
        ITEM_COUNTS.put("MAX_ABILITY_DRAFT_ABILITIES", 48);
    }

    private interface PatchFunc {
        void execute(FieldData field);
    }

    private static final Map<BuildNumberRange, PatchFunc> PATCHES = new LinkedHashMap<>();
    static {
        PATCHES.put(new BuildNumberRange(null, 990), field -> {
            switch (field.nameFunction.apply(0)) {
                case "dirPrimary":
                case "localSound":
                case "m_attachmentPointBoneSpace":
                case "m_attachmentPointRagdollSpace":
                case "m_flElasticity":
                case "m_location":
                case "m_poolOrigin":
                case "m_ragPos":
                case "m_vecEndPos":
                case "m_vecEyeExitEndpoint":
                case "m_vecGunCrosshair":
                case "m_vecLadderDir":
                case "m_vecPlayerMountPositionBottom":
                case "m_vecPlayerMountPositionTop":
                case "m_viewtarget":
                case "m_WorldMaxs":
                case "m_WorldMins":
                case "origin":
                case "vecExtraLocalOrigin":
                case "vecLocalOrigin":
                    field.unpackerProperties.encoderType = "coord";
                    break;

                case "angExtraLocalAngles":
                case "angLocalAngles":
                case "m_angInitialAngles":
                case "m_ragAngles":
                case "m_vLightDirection":
                    field.unpackerProperties.encoderType = "QAngle";
                    break;

                case "m_vecLadderNormal":
                    field.unpackerProperties.encoderType = "normal";
                    break;

                case "m_angRotation":
                    field.unpackerProperties.encoderType = "qangle_pitch_yaw";
                    break;
            }
        });

        PATCHES.put(new BuildNumberRange(1016, 1026), field -> {
            switch (field.nameFunction.apply(0)) {
                case "m_bWorldTreeState":
                case "m_ulTeamLogo":
                case "m_ulTeamBaseLogo":
                case "m_ulTeamBannerLogo":
                case "m_iPlayerIDsInControl":
                case "m_bItemWhiteList":
                case "m_iPlayerSteamID":
                    field.unpackerProperties.encoderType = "fixed64";
            }
        });

        PATCHES.put(new BuildNumberRange(null, null), field -> {
            switch (field.nameFunction.apply(0)) {
                case "m_flSimulationTime":
                case "m_flAnimTime":
                    field.unpackerProperties.encoderType = "simulationtime";
            }
        });

        PATCHES.put(new BuildNumberRange(null, 954), field -> {
            switch (field.nameFunction.apply(0)) {
                case "m_flMana":
                case "m_flMaxMana":
                    UnpackerPropertiesImpl up = field.unpackerProperties;
                    if (up.highValue == 3.4028235E38f) {
                        up.lowValue = null;
                        up.highValue = 8192.0f;
                    }
            }
        });

    }

}