package skadistats.clarity.processor.entities;

import com.google.protobuf.ByteString;
import org.slf4j.Logger;
import skadistats.clarity.ClarityException;
import skadistats.clarity.LogChannel;
import skadistats.clarity.decoder.FieldReader;
import skadistats.clarity.decoder.bitstream.BitStream;
import skadistats.clarity.event.Event;
import skadistats.clarity.event.EventListener;
import skadistats.clarity.event.Initializer;
import skadistats.clarity.event.Insert;
import skadistats.clarity.event.InsertEvent;
import skadistats.clarity.event.Provides;
import skadistats.clarity.logger.PrintfLoggerFactory;
import skadistats.clarity.model.DTClass;
import skadistats.clarity.model.EngineId;
import skadistats.clarity.model.EngineType;
import skadistats.clarity.model.Entity;
import skadistats.clarity.model.FieldPath;
import skadistats.clarity.model.StringTable;
import skadistats.clarity.model.state.ClientFrame;
import skadistats.clarity.model.state.EntityRegistry;
import skadistats.clarity.model.state.EntityState;
import skadistats.clarity.processor.reader.OnMessage;
import skadistats.clarity.processor.reader.OnReset;
import skadistats.clarity.processor.reader.ResetPhase;
import skadistats.clarity.processor.runner.OnInit;
import skadistats.clarity.processor.sendtables.DTClasses;
import skadistats.clarity.processor.sendtables.OnDTClassesComplete;
import skadistats.clarity.processor.sendtables.UsesDTClasses;
import skadistats.clarity.processor.stringtables.OnStringTableEntry;
import skadistats.clarity.util.Predicate;
import skadistats.clarity.util.SimpleIterator;
import skadistats.clarity.wire.common.proto.Demo;
import skadistats.clarity.wire.common.proto.NetMessages;
import skadistats.clarity.wire.common.proto.NetworkBaseTypes;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Pattern;

@Provides({UsesEntities.class, OnEntityCreated.class, OnEntityUpdated.class, OnEntityDeleted.class, OnEntityEntered.class, OnEntityLeft.class, OnEntityUpdatesCompleted.class})
@UsesDTClasses
public class Entities {

    public static final String BASELINE_TABLE = "instancebaseline";

    private static final Logger log = PrintfLoggerFactory.getLogger(LogChannel.entities);

    private int entityCount;
    private FieldReader<DTClass> fieldReader;
    private int[] deletions;
    private int serverTick;
    private EntityRegistry entityRegistry = new EntityRegistry();

    private Map<Integer, ByteString> rawBaselines = new HashMap<>();
    private class Baseline {
        private int dtClassId = -1;
        private EntityState state;
        private void reset() {
            state = null;
        }
        private void copyFrom(Baseline other) {
            this.dtClassId = other.dtClassId;
            this.state = other.state;
        }
    }
    private Baseline[] classBaselines;
    private Baseline[][] entityBaselines;

    private final FieldPath[] updatedFieldPaths = new FieldPath[FieldReader.MAX_PROPERTIES];

    private ClientFrame entities;

    private boolean resetInProgress;
    private ClientFrame.Capsule resetCapsule;

    @Insert
    private EngineType engineType;
    @Insert
    private DTClasses dtClasses;

    @InsertEvent
    private Event<OnEntityCreated> evCreated;
    @InsertEvent
    private Event<OnEntityUpdated> evUpdated;
    @InsertEvent
    private Event<OnEntityDeleted> evDeleted;
    @InsertEvent
    private Event<OnEntityEntered> evEntered;
    @InsertEvent
    private Event<OnEntityLeft> evLeft;
    @InsertEvent
    private Event<OnEntityUpdatesCompleted> evUpdatesCompleted;

    @Initializer(OnEntityCreated.class)
    public void initOnEntityCreated(final EventListener<OnEntityCreated> listener) {
        listener.setInvocationPredicate(getInvocationPredicate(listener.getAnnotation().classPattern()));
    }

    @Initializer(OnEntityDeleted.class)
    public void initOnEntityDeleted(final EventListener<OnEntityDeleted> listener) {
        listener.setInvocationPredicate(getInvocationPredicate(listener.getAnnotation().classPattern()));
    }

    @Initializer(OnEntityUpdated.class)
    public void initOnEntityUpdated(final EventListener<OnEntityUpdated> listener) {
        listener.setInvocationPredicate(getInvocationPredicate(listener.getAnnotation().classPattern()));
    }

    @Initializer(OnEntityEntered.class)
    public void initOnEntityEntered(final EventListener<OnEntityEntered> listener) {
        listener.setInvocationPredicate(getInvocationPredicate(listener.getAnnotation().classPattern()));
    }

    @Initializer(OnEntityLeft.class)
    public void initOnEntityLeft(final EventListener<OnEntityLeft> listener) {
        listener.setInvocationPredicate(getInvocationPredicate(listener.getAnnotation().classPattern()));
    }

    private Predicate<Object[]> getInvocationPredicate(String classPattern) {
        if (".*".equals(classPattern)) {
            return null;
        }
        final Pattern p = Pattern.compile(classPattern);
        return value -> {
            Entity e = (Entity) value[0];
            return p.matcher(e.getDtClass().getDtName()).matches();
        };
    }

    @OnInit
    public void onInit() {
        entityCount = 1 << engineType.getIndexBits();
        entities = new ClientFrame(entityCount);

        fieldReader = engineType.getNewFieldReader();

        deletions = new int[entityCount];

        entityBaselines = new Baseline[entityCount][2];
        for (int i = 0; i < entityCount; i++) {
            entityBaselines[i][0] = new Baseline();
            entityBaselines[i][1] = new Baseline();
        }
    }

    @OnDTClassesComplete
    public void onDTClassesComplete() {
        classBaselines = new Baseline[dtClasses.getClassCount()];
        for (int i = 0; i < classBaselines.length; i++) {
            classBaselines[i] = new Baseline();
            classBaselines[i].dtClassId = i;
        }
    }

    @OnReset
    public void onReset(Demo.CDemoStringTables packet, ResetPhase phase) {
        switch (phase) {
            case START:
                resetInProgress = true;
                resetCapsule = entities.createCapsule();
                break;

            case CLEAR:
                entities = new ClientFrame(entityCount);
                for (int i = 0; i < classBaselines.length; i++) {
                    classBaselines[i].reset();
                }
                for (int i = 0; i < entityBaselines.length; i++) {
                    entityBaselines[i][0].reset();
                    entityBaselines[i][1].reset();
                }
                break;

            case COMPLETE:
                resetInProgress = false;

                //updateEventDebug = true;

                Entity entity;
                for (int eIdx = 0; eIdx < entityCount; eIdx++) {
                    entity = entities.getEntity(eIdx);
                    if (resetCapsule.isExistent(eIdx)) {
                        if (entity == null || entity.getHandle() != resetCapsule.getHandle(eIdx)) {
                            Entity deletedEntity = entityRegistry.get(resetCapsule.getHandle(eIdx));
                            if (resetCapsule.isActive(eIdx)) {
                                emitLeftEvent(deletedEntity);
                            }
                            emitDeletedEvent(deletedEntity);
                        }
                    }
                    if (entity != null) {
                        if (!resetCapsule.isExistent(eIdx) || entity.getHandle() != resetCapsule.getHandle(eIdx)) {
                            emitCreatedEvent(entity);
                            if (entity.isActive()) {
                                emitEnteredEvent(entity);
                            }
                        } else {
                            Iterator<FieldPath> iter = entity.getState().fieldPathIterator();
                            int n = 0;
                            while (iter.hasNext()) {
                                updatedFieldPaths[n++] = iter.next();
                            }
                            emitUpdatedEvent(entity, n);
                        }
                    }
                }

                resetCapsule = null;

                //updateEventDebug = false;

                evUpdatesCompleted.raise();
                break;
        }
    }

    @OnStringTableEntry(BASELINE_TABLE)
    public void onBaselineEntry(StringTable table, int index, String key, ByteString value) {
        Integer dtClassId = Integer.valueOf(key);
        rawBaselines.put(dtClassId, value);
        if (classBaselines != null) {
            classBaselines[dtClassId].reset();
        }
    }

    @OnMessage(NetworkBaseTypes.CNETMsg_Tick.class)
    public void onMessage(NetworkBaseTypes.CNETMsg_Tick message) {
        serverTick = message.getTick();
    }

    boolean debug = false;
    boolean updateEventDebug = false;

    void debugUpdateEvent(String which, Entity entity) {
        if (!updateEventDebug) return;
        log.info("\t%6s: index: %4d, serial: %03x, handle: %7d, class: %s",
                which,
                entity.getIndex(),
                entity.getSerial(),
                entity.getHandle(),
                entity.getDtClass().getDtName()
        );
    }

    @OnMessage(NetMessages.CSVCMsg_PacketEntities.class)
    public void onPacketEntities(NetMessages.CSVCMsg_PacketEntities message) {
        if (log.isDebugEnabled()) {
            log.debug(
                    "processing packet entities: now: %6d, delta-from: %6d, update-count: %5d, baseline: %d, update-baseline: %5s",
                    serverTick,
                    message.getDeltaFrom(),
                    message.getUpdatedEntries(),
                    message.getBaseline(),
                    message.getUpdateBaseline()
            );
        }

        if (message.getIsDelta()) {
            if (serverTick == message.getDeltaFrom()) {
                throw new ClarityException("received self-referential delta update for tick %d", serverTick);
            }
            log.debug("performing delta update, using previous frame from tick %d", message.getDeltaFrom());
        } else {
            log.debug("performing full update");
        }

        if (message.getUpdateBaseline()) {
            int iFrom = message.getBaseline();
            int iTo = 1 - message.getBaseline();
            for (Baseline[] baseline : entityBaselines) {
                baseline[iTo].copyFrom(baseline[iFrom]);
            }
        }

        BitStream stream = BitStream.createBitStream(message.getEntityData());

        int updateCount = message.getUpdatedEntries();
        int updateType;
        int eIdx = -1;
        Entity eEnt;

        while (updateCount-- != 0) {
            eIdx += stream.readUBitVar() + 1;
            eEnt = entities.getEntity(eIdx);

            updateType = stream.readUBitInt(2);
            switch (updateType) {

                case 2: // CREATE
                    int dtClassId = stream.readUBitInt(dtClasses.getClassBits());
                    DTClass dtClass = dtClasses.forClassId(dtClassId);
                    if (dtClass == null) {
                        throw new ClarityException("class for new entity %d is %d, but no dtClass found!.", eIdx, dtClassId);
                    }
                    int serial = stream.readUBitInt(engineType.getSerialBits());
                    if (engineType.getId() == EngineId.SOURCE2) {
                        // TODO: there is an extra VarInt encoded here for S2, figure out what it is
                        stream.readVarUInt();
                    }
                    if (eEnt != null) {
                        if (eEnt.getHandle() == engineType.handleForIndexAndSerial(eIdx, serial)) {
                            if (!eEnt.isActive()) {
                                processEntityEnter(eEnt);
                            }
                            processEntityUpdate(eEnt, stream, false);
                            break;
                        }
                        if (eEnt.isActive()) {
                            processEntityLeave(eEnt);
                        }
                        processEntityDelete(eEnt);
                    }
                    processEntityCreate(eIdx, serial, dtClass, message, stream);
                    break;

                case 0: // UPDATE
                    if (eEnt == null) {
                        int lastHandle = entities.getLastHandle(eIdx);
                        eEnt = entityRegistry.get(lastHandle);
                        processEntityUpdate(eEnt, stream, true);
                        break;
                    }
                    processEntityUpdate(eEnt, stream, false);
                    break;

                case 1: // LEAVE
                    if (eEnt != null && eEnt.isActive()) {
                        processEntityLeave(eEnt);
                    }
                    break;

                case 3: // DELETE
                    if (eEnt != null) {
                        if (eEnt.isActive()) {
                            processEntityLeave(eEnt);
                        }
                        processEntityDelete(eEnt);
                    }
                    break;
            }
        }

        if (engineType.handleDeletions() && message.getIsDelta()) {
            int n = fieldReader.readDeletions(stream, engineType.getIndexBits(), deletions);
            for (int i = 0; i < n; i++) {
                eIdx = deletions[i];
                eEnt = entities.getEntity(eIdx);
                if (eEnt != null) {
                    if (eEnt.isActive()) {
                        processEntityLeave(eEnt);
                    }
                    processEntityDelete(eEnt);
                }
            }
        }

        log.debug("update finished for tick %d", serverTick);

        if (!resetInProgress) {
            evUpdatesCompleted.raise();
        }
    }

    private void processEntityCreate(int eIdx, int serial, DTClass dtClass, NetMessages.CSVCMsg_PacketEntities message, BitStream stream) {
        Baseline baseline = getBaseline(dtClass.getClassId(), message.getBaseline(), eIdx, message.getIsDelta());
        EntityState newState = baseline.state.copy();
        fieldReader.readFields(stream, dtClass, newState, null, debug);
        Entity entity = entityRegistry.create(
                eIdx, serial,
                engineType.handleForIndexAndSerial(eIdx, serial),
                dtClass);
        entity.setExistent(true);
        entity.setState(newState);
        entities.setEntity(entity);
        logModification("CREATE", entity);
        emitCreatedEvent(entity);
        processEntityEnter(entity);
        if (message.getUpdateBaseline()) {
            Baseline updatedBaseline = entityBaselines[eIdx][1 - message.getBaseline()];
            updatedBaseline.dtClassId = dtClass.getClassId();
            updatedBaseline.state = newState.copy();
        }
    }

    private void processEntityUpdate(Entity entity, BitStream stream, boolean silent) {
        assert silent || (entity.isExistent() && entity.isActive());
        int nUpdated = fieldReader.readFields(stream, entity.getDtClass(), entity.getState(), (i, f) -> updatedFieldPaths[i] = f, debug);
        logModification("UPDATE", entity);
        if (!silent) {
            emitUpdatedEvent(entity, nUpdated);
        }
    }

    private void processEntityEnter(Entity entity) {
        assert !entity.isActive();
        entity.setActive(true);
        logModification("ENTER", entity);
        emitEnteredEvent(entity);
    }

    private void processEntityLeave(Entity entity) {
        assert entity.isActive();
        entity.setActive(false);
        logModification("LEAVE", entity);
        emitLeftEvent(entity);
    }

    private void processEntityDelete(Entity entity) {
        assert entity.isExistent();
        entity.setExistent(false);
        entities.removeEntity(entity);
        logModification("DELETE", entity);
        emitDeletedEvent(entity);
    }

    private void emitCreatedEvent(Entity entity) {
        if (resetInProgress || !evCreated.isListenedTo()) return;
        debugUpdateEvent("CREATE", entity);
        evCreated.raise(entity);
    }

    private void emitEnteredEvent(Entity entity) {
        if (resetInProgress || !evEntered.isListenedTo()) return;
        debugUpdateEvent("ENTER", entity);
        evEntered.raise(entity);
    }

    private void emitUpdatedEvent(Entity entity, int nUpdated) {
        if (resetInProgress || !evUpdated.isListenedTo()) return;
        debugUpdateEvent("UPDATE", entity);
        evUpdated.raise(entity, updatedFieldPaths, nUpdated);
    }

    private void emitLeftEvent(Entity entity) {
        if (resetInProgress || !evLeft.isListenedTo()) return;
        debugUpdateEvent("LEAVE", entity);
        evLeft.raise(entity);
    }

    private void emitDeletedEvent(Entity entity) {
        if (resetInProgress || !evDeleted.isListenedTo()) return;
        debugUpdateEvent("DELETE", entity);
        evDeleted.raise(entity);
    }

    private void logModification(String which, Entity entity) {
        if (!log.isDebugEnabled()) return;
        log.debug("\t%6s: index: %4d, serial: %03x, handle: %7d, class: %s",
                which,
                entity.getIndex(),
                entity.getSerial(),
                entity.getHandle(),
                entity.getDtClass().getDtName()
        );
    }

    private Baseline getBaseline(int clsId, int baseline, int entityIdx, boolean delta) {
        Baseline b;
        if (delta) {
            b = entityBaselines[entityIdx][baseline];
            if (b.dtClassId == clsId && b.state != null) {
                return b;
            }
        }
        b = classBaselines[clsId];
        if (b.state != null) {
            return b;
        }
        DTClass cls = dtClasses.forClassId(clsId);
        if (cls == null) {
            throw new ClarityException("DTClass for id %d not found.", clsId);
        }
        b.state = cls.getEmptyState();
        ByteString raw = rawBaselines.get(clsId);
        if (raw == null) {
            throw new ClarityException("Baseline for class %s (%d) not found.", cls.getDtName(), clsId);
        }
        if (raw.size() > 0) {
            BitStream stream = BitStream.createBitStream(raw);
            fieldReader.readFields(stream, cls, b.state, null, false);
        }
        return b;
    }

    public Entity getByIndex(int index) {
        return entities.getEntity(index);
    }

    public Entity getByHandle(int handle) {
        Entity e = getByIndex(engineType.indexForHandle(handle));
        return e == null || e.getHandle() != handle ? null : e;
    }

    public Iterator<Entity> getAllByPredicate(final Predicate<Entity> predicate) {
        return new SimpleIterator<Entity>() {
            int i = -1;

            @Override
            public Entity readNext() {
                while (++i < entityCount) {
                    Entity e = getByIndex(i);
                    if (e != null && predicate.apply(e)) {
                        return e;
                    }
                }
                return null;
            }
        };
    }

    public Entity getByPredicate(Predicate<Entity> predicate) {
        Iterator<Entity> iter = getAllByPredicate(predicate);
        return iter.hasNext() ? iter.next() : null;
    }

    public Iterator<Entity> getAllByDtName(final String dtClassName) {
        return getAllByPredicate(
                e -> dtClassName.equals(e.getDtClass().getDtName()));
    }

    public Entity getByDtName(final String dtClassName) {
        Iterator<Entity> iter = getAllByDtName(dtClassName);
        return iter.hasNext() ? iter.next() : null;
    }

}
