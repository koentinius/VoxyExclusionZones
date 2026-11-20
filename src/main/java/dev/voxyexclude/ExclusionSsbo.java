package dev.voxyexclude;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.lwjgl.opengl.GL11C.glGetInteger;
import static org.lwjgl.opengl.GL15C.glBindBuffer;
import static org.lwjgl.opengl.GL30C.glBindBufferBase;
import static org.lwjgl.opengl.GL30C.glGetIntegeri;
import static org.lwjgl.opengl.GL32C.glGetInteger64;
import static org.lwjgl.opengl.GL42C.GL_BUFFER_UPDATE_BARRIER_BIT;
import static org.lwjgl.opengl.GL42C.glMemoryBarrier;
import static org.lwjgl.opengl.GL43C.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS;
import static org.lwjgl.opengl.GL43C.GL_MAX_SHADER_STORAGE_BLOCK_SIZE;
import static org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BARRIER_BIT;
import static org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BUFFER_BINDING;
import static org.lwjgl.opengl.GL44C.GL_DYNAMIC_STORAGE_BIT;
import static org.lwjgl.opengl.GL45C.glCreateBuffers;
import static org.lwjgl.opengl.GL45C.glDeleteBuffers;
import static org.lwjgl.opengl.GL45C.glNamedBufferStorage;
import static org.lwjgl.opengl.GL45C.glNamedBufferSubData;

public final class ExclusionSsbo {
    public static final int BINDING_INDEX = 11;

    private int bufferId;
    private long capacity;
    private long uploadedRevision = Long.MIN_VALUE;

    public record BindingState(int previousIndexedBinding, int previousGenericBinding) {}

    public static void assertBindingSupported() {
        int bindingCount = glGetInteger(GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS);
        if (bindingCount <= BINDING_INDEX) {
            throw new IllegalStateException(
                    "Voxy Exclusion Zones requires at least 12 shader-storage buffer bindings; GPU reports " + bindingCount
            );
        }
    }

    public BindingState bind() {
        assertBindingSupported();

        int previousIndexed = glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, BINDING_INDEX);
        int previousGeneric = glGetInteger(GL_SHADER_STORAGE_BUFFER_BINDING);

        ZoneManager.EnabledSnapshot snapshot = ZoneManager.enabledSnapshot();
        uploadIfNeeded(snapshot);

        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, BINDING_INDEX, this.bufferId);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_BUFFER_UPDATE_BARRIER_BIT);
        return new BindingState(previousIndexed, previousGeneric);
    }

    public void restore(BindingState state) {
        if (state == null) {
            return;
        }
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, BINDING_INDEX, state.previousIndexedBinding());
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, state.previousGenericBinding());
    }

    public void free() {
        if (this.bufferId != 0) {
            glDeleteBuffers(this.bufferId);
            this.bufferId = 0;
            this.capacity = 0;
            this.uploadedRevision = Long.MIN_VALUE;
        }
    }

    private void uploadIfNeeded(ZoneManager.EnabledSnapshot snapshot) {
        if (this.bufferId != 0 && this.uploadedRevision == snapshot.revision()) {
            return;
        }

        List<Zone> zones = snapshot.zones();
        long requiredBytes = 16L + 48L * zones.size();
        long maxBlockSize = glGetInteger64(GL_MAX_SHADER_STORAGE_BLOCK_SIZE);
        if (requiredBytes > maxBlockSize) {
            throw new IllegalStateException("Enabled exclusion zones require " + requiredBytes
                    + " bytes, exceeding GL_MAX_SHADER_STORAGE_BLOCK_SIZE (" + maxBlockSize + ")");
        }

        ensureCapacity(requiredBytes, maxBlockSize);

        int byteCount = Math.toIntExact(requiredBytes);
        ByteBuffer data = MemoryUtil.memAlloc(byteCount).order(ByteOrder.nativeOrder());
        try {
            data.putInt(zones.size()).putInt(0).putInt(0).putInt(0);
            for (Zone zone : zones) {
                data.putInt(Math.floorDiv(zone.centerX(), 32));
                data.putInt(Math.floorDiv(zone.centerZ(), 32));
                data.putInt(Math.floorMod(zone.centerX(), 32));
                data.putInt(Math.floorMod(zone.centerZ(), 32));

                data.putInt(zone.type() == Zone.Type.CIRCLE ? 0 : 1);
                data.putInt(zone.size());
                data.putInt(zone.surfaceEnabled() ? 1 : 0);
                data.putInt(zone.surfaceY());

                data.putInt(zone.surfaceMaterial().ordinal());
                data.putInt(0);
                data.putInt(0);
                data.putInt(0);
            }
            data.flip();
            glNamedBufferSubData(this.bufferId, 0L, data);
            this.uploadedRevision = snapshot.revision();
        } finally {
            MemoryUtil.memFree(data);
        }
    }

    private void ensureCapacity(long requiredBytes, long maxBlockSize) {
        if (this.bufferId != 0 && this.capacity >= requiredBytes) {
            return;
        }

        long newCapacity = 256;
        while (newCapacity < requiredBytes && newCapacity < maxBlockSize) {
            newCapacity = Math.min(newCapacity << 1, maxBlockSize);
        }
        if (newCapacity < requiredBytes) {
            throw new IllegalStateException("Cannot allocate exclusion-zone SSBO of " + requiredBytes + " bytes");
        }

        int newBuffer = glCreateBuffers();
        try {
            glNamedBufferStorage(newBuffer, newCapacity, GL_DYNAMIC_STORAGE_BIT);
        } catch (RuntimeException | Error e) {
            glDeleteBuffers(newBuffer);
            throw e;
        }

        int oldBuffer = this.bufferId;
        this.bufferId = newBuffer;
        this.capacity = newCapacity;
        if (oldBuffer != 0) {
            glDeleteBuffers(oldBuffer);
        }
    }
}
