package skadistats.clarity.decoder.s2.field;

import skadistats.clarity.decoder.s2.field.iface.Unpackable;
import skadistats.clarity.decoder.unpacker.Unpacker;

public class ValueField extends Field2 implements Unpackable {

    private final Unpacker<?> unpacker;

    public ValueField(FieldProperties fieldProperties, UnpackerProperties unpackerProperties, Unpacker<?> unpacker) {
        super(fieldProperties, unpackerProperties);
        this.unpacker = unpacker;
    }

    @Override
    public Field2 down(int i) {
        return null;
    }

    @Override
    public Unpacker<?> getUnpacker() {
        return unpacker;
    }

}
