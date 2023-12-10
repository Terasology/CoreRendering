// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.corerendering.rendering.dag.nodes;

import org.terasology.engine.context.Context;
import org.terasology.gestalt.naming.Name;
import org.terasology.engine.rendering.dag.AbstractNode;
import org.terasology.engine.rendering.dag.stateChanges.BindFbo;
import org.terasology.engine.rendering.opengl.BaseFboManager;
import org.terasology.engine.rendering.opengl.FBO;
import org.terasology.engine.rendering.opengl.FboConfig;

import static org.lwjgl.opengl.GL11.glClear;

/**
 * Instances of this node clear specific buffers attached to an FBOs, in accordance to a clearing mask.
 * Normally this means that all the pixels in the buffers selected by the mask are reset to a default value.
 *
 * This class could be inherited by a more specific class that sets the default values, via (yet to be written)
 * state changes.
 */
public class BufferClearingNode extends AbstractNode {
    private int clearingMask;
    private FBO fbo;
    /**
     * @deprecated
     * Constructs the node by requesting the creation (if necessary) of the FBO to be cleared
     * and by requesting for this FBO to be bound by the time process() gets executed. Also
     * stores the clearing mask, for use in process().
     *
     * @param fboConfig an FboConfig object characterizing the FBO to act upon, if necessary prompting its creation.
     * @param fboManager an instance implementing the BaseFboManager interface, used to retrieve and bind the FBO.
     * @param clearingMask a glClear(int)-compatible mask, selecting which FBO-attached buffers to clear,
     *                      i.e. "GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT". This argument can't be zero.
     *                      Non GL_*_BIT values will be accepted but might eventually generate an opengl error.
     * @throws IllegalArgumentException if fboConfig, fboManager are null and if clearingMask is zero.
     */@Deprecated
    public BufferClearingNode(String nodeUri,  Name providingModule, Context context, FboConfig fboConfig, BaseFboManager fboManager,
                              int clearingMask) {
        super(nodeUri, providingModule, context);

        boolean argumentsAreValid = validateArguments(fboConfig, fboManager, clearingMask);

        if (argumentsAreValid) {
            this.fbo = requiresFbo(fboConfig, fboManager);
            addOutputFboConnection(1, fbo);
            // addDesiredStateChange(new BindFbo(fbo));
            this.clearingMask = clearingMask;
        } else {
            throw new IllegalArgumentException("Illegal argument(s): see the log for details.");
        }
    }

    /**
     * For passing the FBO by renderGraph.connectInputFbo
     * @param nodeUri
     * @param context
     * @param clearingMask
     */
    public BufferClearingNode(String nodeUri, Context context, Name providingModule, int clearingMask) {
        super(nodeUri, providingModule, context);
        this.clearingMask = clearingMask;
        addOutputFboConnection(1);
    }

    @Deprecated
    public BufferClearingNode(String nodeUri, Context context, Name providingModule, FBO fbo, int clearingMask) {
        super(nodeUri, providingModule, context);

        boolean argumentsAreValid = validateArguments(fbo, clearingMask);

        if (argumentsAreValid) {
            this.fbo = fbo;
            addOutputFboConnection(1, fbo);
            // addDesiredStateChange(new BindFbo(fbo));
            this.clearingMask = clearingMask;
        } else {
            throw new IllegalArgumentException("Illegal argument(s): see the log for details.");
        }
    }


    /**
     * Clears the buffers selected by the mask provided in setRequiredObjects, with default values.
     * <p>
     * This method is executed within a NodeTask in the Render Tasklist.
     */
    @Override
    public void process() {
        glClear(clearingMask);
    }

    private boolean validateArguments(FboConfig fboConfig, BaseFboManager fboManager, int clearingMask) {
        boolean argumentsAreValid = true;

        if (fboConfig == null) {
            argumentsAreValid = false;
            logger.warn("Illegal argument: fboConfig shouldn't be null.");
        }

        if (fboManager == null) {
            argumentsAreValid = false;
            logger.warn("Illegal argument: fboManager shouldn't be null.");
        }

        if (clearingMask == 0) {
            argumentsAreValid = false;
            logger.warn("Illegal argument: clearingMask can't be 0.");
        }

        return argumentsAreValid;
    }

    private boolean validateArguments(FBO fbo, int clearingMask) {
        boolean argumentsAreValid = true;

        if (fbo == null) {
            argumentsAreValid = false;
            logger.warn("Illegal argument: fbo shouldn't be null.");
        }

        if (clearingMask == 0) {
            argumentsAreValid = false;
            logger.warn("Illegal argument: clearingMask can't be 0.");
        }

        return argumentsAreValid;
    }

    @Override
    public void setDependencies(Context context) {
        // TODO there should be a better way to distinguish between usages. Ideally, all usages should be the same/
        if (fbo == null) {
            fbo = getInputFboData(1);
        }
        // TODO this will be redundant for bufferClearingNodes created trough older constructors
        addDesiredStateChange(new BindFbo(fbo));
        addOutputFboConnection(1, fbo);
    }
}
