/*
 ***** BEGIN LICENSE BLOCK *****
 * Version: CPL 1.0/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Common Public
 * License Version 1.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of
 * the License at http://www.eclipse.org/legal/cpl-v10.html
 *
 * Software distributed under the License is distributed on an "AS
 * IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * rights and limitations under the License.
 *
 * Copyright (C) 2001 Chad Fowler <chadfowler@chadfowler.com>
 * Copyright (C) 2001 Alan Moore <alan_moore@gmx.net>
 * Copyright (C) 2001-2002 Benoit Cerrina <b.cerrina@wanadoo.fr>
 * Copyright (C) 2001-2004 Jan Arne Petersen <jpetersen@uni-bonn.de>
 * Copyright (C) 2002-2004 Anders Bengtsson <ndrsbngtssn@yahoo.se>
 * Copyright (C) 2004 Thomas E Enebo <enebo@acm.org>
 * Copyright (C) 2004-2005 Charles O Nutter <headius@headius.com>
 * Copyright (C) 2004 Stefan Matthias Aust <sma@3plus4.de>
 * Copyright (C) 2006-2007 Miguel Covarrubias <mlcovarrubias@gmail.com>
 * Copyright (C) 2007 William N Dortch <bill.dortch@gmail.com>
 * 
 * Alternatively, the contents of this file may be used under the terms of
 * either of the GNU General Public License Version 2 or later (the "GPL"),
 * or the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the CPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the CPL, the GPL or the LGPL.
 ***** END LICENSE BLOCK *****/
package org.jruby;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jruby.anno.JRubyMethod;
import org.jruby.internal.runtime.methods.AliasMethod;
import org.jruby.internal.runtime.methods.DynamicMethod;
import org.jruby.internal.runtime.methods.FullFunctionCallbackMethod;
import org.jruby.internal.runtime.methods.SimpleCallbackMethod;
import org.jruby.internal.runtime.methods.MethodMethod;
import org.jruby.internal.runtime.methods.ProcMethod;
import org.jruby.internal.runtime.methods.UndefinedMethod;
import org.jruby.internal.runtime.methods.WrapperMethod;
import org.jruby.parser.StaticScope;
import org.jruby.runtime.Arity;
import org.jruby.runtime.Block;
import org.jruby.runtime.CallbackFactory;
import org.jruby.runtime.Dispatcher;
import org.jruby.runtime.ObjectAllocator;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.Visibility;
import org.jruby.runtime.builtin.Variable;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.runtime.callback.Callback;
import org.jruby.runtime.component.VariableStore;
import org.jruby.runtime.component.ConcurrentObjectVariableStore;
import org.jruby.runtime.marshal.MarshalStream;
import org.jruby.runtime.marshal.UnmarshalStream;
import org.jruby.util.ClassProvider;
import org.jruby.util.IdUtil;
import org.jruby.util.MethodCache;
import org.jruby.exceptions.RaiseException;
import org.jruby.runtime.ClassIndex;
import org.jruby.runtime.MethodIndex;

/**
 *
 * @author  jpetersen
 */
public class RubyModule extends RubyObject {
    private static final String CVAR_TAINT_ERROR =
        "Insecure: can't modify class variable";
    private static final String CVAR_FREEZE_ERROR = "class/module";

    // superClass may be null.
    private RubyClass superClass;

    public int index;
    
    public Dispatcher dispatcher = Dispatcher.DEFAULT_DISPATCHER;

    public static class KindOf {
        public static final KindOf DEFAULT_KIND_OF = new KindOf();
        public boolean isKindOf(IRubyObject obj, RubyModule type) {
            return obj.getMetaClass().hasModuleInHierarchy(type);
        }
    }

    public KindOf kindOf = KindOf.DEFAULT_KIND_OF;

    public final int id;

    // Containing class...The parent of Object is null. Object should always be last in chain.
    public RubyModule parent;

    // ClassId is the name of the class/module sans where it is located.
    // If it is null, then it an anonymous class.
    private String classId;

    // All methods and all CACHED methods for the module.  The cached methods will be removed
    // when appropriate (e.g. when method is removed by source class or a new method is added
    // with same name by one of its subclasses).
    private Map methods = new HashMap();
    
    // FIXME: I'm not sure what the serialization/marshalling implications
    // might be of defining this here. We could keep a hash in JavaSupport
    // (or elsewhere) instead, but then RubyModule might need a reference to 
    // JavaSupport code, which I've tried to avoid...
    private transient List classProviders;
    
    // synchronized method per JRUBY-1173 (unsafe Double-Checked Locking)
    public synchronized void addClassProvider(ClassProvider provider) {
        if (classProviders == null) {
            List cp = Collections.synchronizedList(new ArrayList());
            cp.add(provider);
            classProviders = cp;
        } else {
            synchronized(classProviders) {
                if (!classProviders.contains(provider)) {
                    classProviders.add(provider);
                }
            }
        }
    }

    public void removeClassProvider(ClassProvider provider) {
        if (classProviders != null) {
            classProviders.remove(provider);
        }
    }

    private RubyClass searchClassProviders(String name, RubyClass superClazz) {
        if (classProviders != null) {
            synchronized(classProviders) {
                RubyClass clazz;
                for (Iterator iter = classProviders.iterator(); iter.hasNext(); ) {
                    if ((clazz = ((ClassProvider)iter.next())
                            .defineClassUnder(this, name, superClazz)) != null) {
                        return clazz;
                    }
                }
            }
        }
        return null;
    }

    protected RubyModule(Ruby runtime, RubyClass metaClass, RubyClass superClass, RubyModule parent, String name) {
        this(runtime, metaClass, superClass, parent, name, runtime.isObjectSpaceEnabled());
    }

    protected RubyModule(Ruby runtime, RubyClass metaClass, RubyClass superClass, RubyModule parent, String name, boolean useObjectSpace) {
        super(runtime, metaClass, useObjectSpace);

        this.superClass = superClass;

        setBaseName(name);

        // If no parent is passed in, it is safe to assume Object.
        if (parent == null) {
            if (runtime.getObject() != null) {
                parent = runtime.getObject();
            }
        }
        this.parent = parent;
        
        this.variableStore = new ConcurrentObjectVariableStore<IRubyObject>(runtime, this, 16);

        runtime.moduleLastId++;
        this.id = runtime.moduleLastId;
    }

    @Override
    protected VariableStore<IRubyObject> ensureVariableStore() {
        return variableStore;
    }
    
    public static RubyClass createModuleClass(Ruby runtime, RubyClass moduleClass) {
        CallbackFactory callbackFactory = runtime.callbackFactory(RubyModule.class);   
        RubyClass moduleMetaClass = moduleClass.getMetaClass();
        moduleClass.index = ClassIndex.MODULE;
        moduleClass.kindOf = new RubyModule.KindOf() {
                public boolean isKindOf(IRubyObject obj, RubyModule type) {
                    return obj instanceof RubyModule;
                }
            };

        moduleClass.defineFastMethod("===", callbackFactory.getFastMethod("op_eqq", IRubyObject.class));
        moduleClass.defineFastMethod("<=>", callbackFactory.getFastMethod("op_cmp", IRubyObject.class));
        moduleClass.defineFastMethod("<", callbackFactory.getFastMethod("op_lt", IRubyObject.class));
        moduleClass.defineFastMethod("<=", callbackFactory.getFastMethod("op_le", IRubyObject.class));
        moduleClass.defineFastMethod(">", callbackFactory.getFastMethod("op_gt", IRubyObject.class));
        moduleClass.defineFastMethod(">=", callbackFactory.getFastMethod("op_ge", IRubyObject.class));
        moduleClass.defineFastMethod("ancestors", callbackFactory.getFastMethod("ancestors"));
        moduleClass.defineFastMethod("class_variables", callbackFactory.getFastMethod("class_variables"));
        moduleClass.defineFastMethod("const_defined?", callbackFactory.getFastMethod("const_defined", IRubyObject.class));
        moduleClass.defineFastMethod("const_get", callbackFactory.getFastMethod("const_get", IRubyObject.class));
        moduleClass.defineMethod("const_missing", callbackFactory.getMethod("const_missing", IRubyObject.class));
        moduleClass.defineFastMethod("const_set", callbackFactory.getFastMethod("const_set", IRubyObject.class, IRubyObject.class));
        moduleClass.defineFastMethod("constants", callbackFactory.getFastMethod("constants"));
        moduleClass.defineMethod("extended", callbackFactory.getMethod("extended", IRubyObject.class));
        moduleClass.defineFastMethod("include?", callbackFactory.getFastMethod("include_p", IRubyObject.class));
        moduleClass.defineFastMethod("included", callbackFactory.getFastMethod("included", IRubyObject.class));
        moduleClass.defineFastMethod("included_modules", callbackFactory.getFastMethod("included_modules"));
        moduleClass.defineMethod("initialize", callbackFactory.getOptMethod("initialize"));
        moduleClass.defineFastMethod("initialize_copy", callbackFactory.getFastMethod("initialize_copy", IRubyObject.class));
        moduleClass.defineFastMethod("instance_method", callbackFactory.getFastMethod("instance_method", IRubyObject.class));
        moduleClass.defineFastMethod("instance_methods",callbackFactory.getFastOptMethod("instance_methods"));
        moduleClass.defineFastMethod("method_defined?", callbackFactory.getFastMethod("method_defined", IRubyObject.class));
        moduleClass.defineFastMethod("public_method_defined?", callbackFactory.getFastMethod("public_method_defined", IRubyObject.class));
        moduleClass.defineFastMethod("protected_method_defined?", callbackFactory.getFastMethod("protected_method_defined", IRubyObject.class));
        moduleClass.defineFastMethod("private_method_defined?", callbackFactory.getFastMethod("private_method_defined", IRubyObject.class));
        moduleClass.defineMethod("module_eval", callbackFactory.getOptMethod("module_eval"));
        moduleClass.defineFastMethod("name", callbackFactory.getFastMethod("name"));
        moduleClass.defineFastMethod("private_class_method", callbackFactory.getFastOptMethod("private_class_method"));
        moduleClass.defineFastMethod("private_instance_methods", callbackFactory.getFastOptMethod("private_instance_methods"));
        moduleClass.defineFastMethod("protected_instance_methods", callbackFactory.getFastOptMethod("protected_instance_methods"));
        moduleClass.defineFastMethod("public_class_method", callbackFactory.getFastOptMethod("public_class_method"));
        moduleClass.defineFastMethod("public_instance_methods", callbackFactory.getFastOptMethod("public_instance_methods"));
        moduleClass.defineFastMethod("to_s", callbackFactory.getFastMethod("to_s"));
        moduleClass.defineFastMethod("class_variable_defined?", callbackFactory.getFastMethod("class_variable_defined_p", IRubyObject.class));
        
        moduleClass.defineAlias("class_eval", "module_eval");
        
        moduleClass.defineFastPrivateMethod("alias_method", callbackFactory.getFastMethod("alias_method", IRubyObject.class, IRubyObject.class));
        moduleClass.defineFastPrivateMethod("append_features", callbackFactory.getFastMethod("append_features", IRubyObject.class));
        moduleClass.defineFastPrivateMethod("attr", callbackFactory.getFastOptMethod("attr"));
        moduleClass.defineFastPrivateMethod("attr_reader", callbackFactory.getFastOptMethod("attr_reader"));
        moduleClass.defineFastPrivateMethod("attr_writer", callbackFactory.getFastOptMethod("attr_writer"));
        moduleClass.defineFastPrivateMethod("attr_accessor", callbackFactory.getFastOptMethod("attr_accessor"));
        moduleClass.defineFastPrivateMethod("class_variable_get", callbackFactory.getFastMethod("class_variable_get", IRubyObject.class));
        moduleClass.defineFastPrivateMethod("class_variable_set", callbackFactory.getFastMethod("class_variable_set", IRubyObject.class, IRubyObject.class));
        moduleClass.definePrivateMethod("define_method", callbackFactory.getOptMethod("define_method"));
        moduleClass.defineFastPrivateMethod("extend_object", callbackFactory.getFastMethod("extend_object", IRubyObject.class));
        moduleClass.defineFastPrivateMethod("include", callbackFactory.getFastOptMethod("include"));
        moduleClass.definePrivateMethod("method_added", callbackFactory.getMethod("method_added", IRubyObject.class));
        moduleClass.definePrivateMethod("method_removed", callbackFactory.getMethod("method_removed", IRubyObject.class));
        moduleClass.definePrivateMethod("method_undefined", callbackFactory.getMethod("method_undefined", IRubyObject.class));
        moduleClass.defineFastPrivateMethod("module_function", callbackFactory.getFastOptMethod("module_function"));
        moduleClass.defineFastPrivateMethod("public", callbackFactory.getFastOptMethod("rbPublic"));
        moduleClass.defineFastPrivateMethod("protected", callbackFactory.getFastOptMethod("rbProtected"));
        moduleClass.defineFastPrivateMethod("private", callbackFactory.getFastOptMethod("rbPrivate"));
        moduleClass.defineFastPrivateMethod("remove_class_variable", callbackFactory.getFastMethod("remove_class_variable", IRubyObject.class));
        moduleClass.defineFastPrivateMethod("remove_const", callbackFactory.getFastMethod("remove_const", IRubyObject.class));
        moduleClass.defineFastPrivateMethod("remove_method", callbackFactory.getFastOptMethod("remove_method"));
        moduleClass.defineFastPrivateMethod("undef_method", callbackFactory.getFastOptMethod("undef_method"));
        
        moduleMetaClass.defineMethod("nesting", callbackFactory.getSingletonMethod("nesting"));
        
        moduleClass.dispatcher = callbackFactory.createDispatcher(moduleClass);

        callbackFactory = runtime.callbackFactory(RubyKernel.class);
        moduleClass.defineFastMethod("autoload", callbackFactory.getFastSingletonMethod("autoload", RubyKernel.IRUBY_OBJECT, RubyKernel.IRUBY_OBJECT));
        moduleClass.defineFastMethod("autoload?", callbackFactory.getFastSingletonMethod("autoload_p", RubyKernel.IRUBY_OBJECT));

        return moduleClass;
    }    
    
    static ObjectAllocator MODULE_ALLOCATOR = new ObjectAllocator() {
        public IRubyObject allocate(Ruby runtime, RubyClass klass) {
            return RubyModule.newModule(runtime, klass, null);
        }
    };
    
    public int getNativeTypeIndex() {
        return ClassIndex.MODULE;
    }
    
    public Dispatcher getDispatcher() {
        return dispatcher;
    }

    /** Getter for property superClass.
     * @return Value of property superClass.
     */
    public RubyClass getSuperClass() {
        return superClass;
    }

    protected void setSuperClass(RubyClass superClass) {
        this.superClass = superClass;
    }

    public RubyModule getParent() {
        return parent;
    }

    public void setParent(RubyModule parent) {
        this.parent = parent;
    }

    public Map getMethods() {
        return methods;
    }
    
    public void putMethod(Object name, DynamicMethod method) {
        // FIXME: kinda hacky...flush STI here
        dispatcher.clearIndex(MethodIndex.getIndex((String)name));
        getMethods().put(name, method);
    }

    public boolean isModule() {
        return true;
    }

    public boolean isClass() {
        return false;
    }

    public boolean isSingleton() {
        return false;
    }

    /**
     * Is this module one that in an included one (e.g. an IncludedModuleWrapper). 
     */
    public boolean isIncluded() {
        return false;
    }

    public RubyModule getNonIncludedClass() {
        return this;
    }

    public String getBaseName() {
        return classId;
    }

    public void setBaseName(String name) {
        classId = name;
    }

    /**
     * Generate a fully-qualified class name or a #-style name for anonymous and singleton classes.
     * 
     * Ruby C equivalent = "classname"
     * 
     * @return The generated class name
     */
    public String getName() {
        if (getBaseName() == null) {
            if (isClass()) {
                return "#<" + "Class" + ":01x" + Integer.toHexString(System.identityHashCode(this)) + ">";
            } else {
                return "#<" + "Module" + ":01x" + Integer.toHexString(System.identityHashCode(this)) + ">";
            }
        }

        StringBuffer result = new StringBuffer(getBaseName());
        RubyClass objectClass = getRuntime().getObject();

        for (RubyModule p = this.getParent(); p != null && p != objectClass; p = p.getParent()) {
            String pName = p.getBaseName();
            // This is needed when the enclosing class or module is a singleton.
            // In that case, we generated a name such as null::Foo, which broke 
            // Marshalling, among others. The correct thing to do in this situation 
            // is to insert the generate the name of form #<Class:01xasdfasd> if 
            // it's a singleton module/class, which this code accomplishes.
            if(pName == null) {
                pName = p.getName();
            }
            result.insert(0, "::").insert(0, pName);
        }

        return result.toString();
    }

    /**
     * Create a wrapper to use for including the specified module into this one.
     * 
     * Ruby C equivalent = "include_class_new"
     * 
     * @return The module wrapper
     */
    public IncludedModuleWrapper newIncludeClass(RubyClass superClazz) {
        IncludedModuleWrapper includedModule = new IncludedModuleWrapper(getRuntime(), superClazz, this);

        // include its parent (and in turn that module's parents)
        if (getSuperClass() != null) {
            includedModule.includeModule(getSuperClass());
        }

        return includedModule;
    }
    
    //
    // INTERNAL VARIABLES
    //
    
    /**
     * Behaves similarly to {@link #getClassVar(String)}. Searches this
     * class/module <em>and its ancestors</em> for the specified internal
     * variable.
     * 
     * @param name the internal variable name
     * @return the value of the specified internal variable if found, else null
     * @see #setInternalModuleVariable(String, IRubyObject)
     */
    public boolean hasInternalModuleVariable(final String name) {
        for (RubyModule mod = this; mod != null; mod = mod.getSuperClass()) {
            if (mod.hasInternalVariable(name)) {
                return true;
            }
        }
        return false;
    }
    /**
     * Behaves similarly to {@link #getClassVar(String)}. Searches this
     * class/module <em>and its ancestors</em> for the specified internal
     * variable.
     * 
     * @param name the internal variable name
     * @return the value of the specified internal variable if found, else null
     * @see #setInternalModuleVariable(String, IRubyObject)
     */
    public IRubyObject searchInternalModuleVariable(final String name) {
        for (RubyModule mod = this; mod != null; mod = mod.getSuperClass()) {
            IRubyObject value;
            if ((value = mod.getInternalVariable(name)) != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * Behaves similarly to {@link #setClassVar(String, IRubyObject)}. If the
     * specified internal variable is found in this class/module <em>or an ancestor</em>,
     * it is set where found.  Otherwise it is set in this module. 
     * 
     * @param name the internal variable name
     * @param value the internal variable value
     * @see #searchInternalModuleVariable(String)
     */
    public void setInternalModuleVariable(final String name, final IRubyObject value) {
        for (RubyModule mod = this; mod != null; mod = mod.getSuperClass()) {
            if (mod.hasInternalVariable(name)) {
                mod.setInternalVariable(name, value);
                return;
            }
        }
        setInternalVariable(name, value);
    }
    
    //
    // CLASS VARIABLES
    //
    
    public boolean hasLocalClassVariable(final String name) {
        return getVariableStore().hasClassVariable(name);
    }

    public boolean fastHasLocalClassVariable(final String internedName) {
        return getVariableStore().fastHasClassVariable(internedName);
    }
    
    public IRubyObject getLocalClassVariable(final String name) {
        return getVariableStore().getClassVariable(name);
    }

    public IRubyObject fastGetLocalClassVariable(final String internedName) {
        return getVariableStore().fastGetClassVariable(internedName);
    }
    
    public void setLocalClassVariable(final String name, final IRubyObject value) {
        getVariableStore().setClassVariable(name, value);
    }

    public void fastSetLocalClassVariable(final String internedName, final IRubyObject value) {
        getVariableStore().fastSetClassVariable(internedName, value);
    }
    
    public List<String> getLocalClassVariableNameList() {
        return getVariableStore().getClassVariableNameList();
    }

    /**
     * Search this and parent modules for the named class variable.
     * 
     * @param name The variable to search for
     * @return The module in which that variable is found, or null if not found
     */
    private RubyModule getModuleWithClassVar(final String name) {
        for (RubyModule p = this; p != null; p = p.getSuperClass()) {
            if (p.hasLocalClassVariable(name)) {
                return p;
            }
        }
        return null;
    }

    private RubyModule fastGetModuleWithClassVar(final String internedName) {
        for (RubyModule p = this; p != null; p = p.getSuperClass()) {
            if (p.fastHasLocalClassVariable(internedName)) {
                return p;
            }
        }
        return null;
    }

    /**
     * Set the named class variable to the given value, provided taint and freeze allow setting it.
     * 
     * Ruby C equivalent = "rb_cvar_set"
     * 
     * @param name The variable name to set
     * @param value The value to set it to
     */
    public IRubyObject setClassVar(final String name, final IRubyObject value) {
        RubyModule module;

        if ((module = getModuleWithClassVar(name)) == null) {
            module = this;
        }

        module.getVariableStore().setClassVariable(name, value);
        return value;
    }

    public IRubyObject fastSetClassVar(final String internedName, final IRubyObject value) {
        RubyModule module;

        if ((module = fastGetModuleWithClassVar(internedName)) == null) {
            module = this;
        }
        module.getVariableStore().fastSetClassVariable(internedName, value);
        return value;
    }

    /**
     * Retrieve the specified class variable, searching through this module, included modules, and supermodules.
     * 
     * Ruby C equivalent = "rb_cvar_get"
     * 
     * @param name The name of the variable to retrieve
     * @return The variable's value, or throws NameError if not found
     */
    public IRubyObject getClassVar(final String name) {
        final RubyModule module;

        if ((module = getModuleWithClassVar(name)) != null) {
            IRubyObject value;
            if ((value = module.getVariableStore().getClassVariable(name)) != null) {
                return value;
            }
            return getRuntime().getNil();
        }

        throw getRuntime().newNameError("uninitialized class variable " + name + " in " + getName(), name);
    }

    public IRubyObject fastGetClassVar(final String internedName) {
        final RubyModule module;

        if ((module = fastGetModuleWithClassVar(internedName)) != null) {
            IRubyObject value;
            if ((value = module.getVariableStore().fastGetClassVariable(internedName)) != null) {
                return value;
            }
            return getRuntime().getNil();
        }

        throw getRuntime().newNameError("uninitialized class variable " + internedName + " in " + getName(), internedName);
    }

    /**
     * Is class var defined?
     * 
     * Ruby C equivalent = "rb_cvar_defined"
     * 
     * @param name The class var to determine "is defined?"
     * @return true if true, false if false
     */
    public boolean isClassVarDefined(String name) {
        return getModuleWithClassVar(name) != null;
    }

    public boolean fastIsClassVarDefined(String internedName) {
        return fastGetModuleWithClassVar(internedName) != null;
    }

    /**
     * Finds a class that is within the current module (or class).
     * 
     * @param name to be found in this module (or class)
     * @return the class or null if no such class
     */
    public RubyClass getClass(final String name) {
        final IRubyObject module;
        if ((module = getConstantAt(name)) instanceof RubyClass) {
            return (RubyClass) module;
        }
        return null;
    }

    public RubyClass fastGetClass(final String internedName) {
        final IRubyObject module;
        if ((module = fastGetConstantAt(internedName)) instanceof RubyClass) {
            return (RubyClass) module;
        }
        return null;
    }

    /**
     * Include a new module in this module or class.
     * 
     * @param arg The module to include
     */
    public synchronized void includeModule(IRubyObject arg) {
        assert arg != null;

        testFrozen("module");
        if (!isTaint()) {
            getRuntime().secure(4);
        }

        if (!(arg instanceof RubyModule)) {
            throw getRuntime().newTypeError("Wrong argument type " + arg.getMetaClass().getName() +
                    " (expected Module).");
        }

        RubyModule module = (RubyModule) arg;

        // Make sure the module we include does not already exist
        if (isSame(module)) {
            return;
        }

        infectBy(module);

        RubyModule p, c;
        boolean changed = false;
        boolean skip = false;

        c = this;
        while (module != null) {
            if (getNonIncludedClass() == module.getNonIncludedClass()) {
                throw getRuntime().newArgumentError("cyclic include detected");
            }

            boolean superclassSeen = false;
            for (p = getSuperClass(); p != null; p = p.getSuperClass()) {
                if (p instanceof IncludedModuleWrapper) {
                    if (p.getNonIncludedClass() == module.getNonIncludedClass()) {
                        if (!superclassSeen) {
                            c = p;
                        }
                        skip = true;
                        break;
                    }
                } else {
                    superclassSeen = true;
                }
            }
            if (!skip) {
                // In the current logic, if we get here we know that module is not an 
                // IncludedModuleWrapper, so there's no need to fish out the delegate. But just 
                // in case the logic should change later, let's do it anyway:
                c.setSuperClass(new IncludedModuleWrapper(getRuntime(), c.getSuperClass(),
                        module.getNonIncludedClass()));
                c = c.getSuperClass();
                changed = true;
            }

            module = module.getSuperClass();
            skip = false;
        }

        if (changed) {
            getRuntime().getMethodCache().clearCache();
            
            // MRI seems to blow away its cache completely after an include; is
            // what we're doing here really safe?
            // CON: clearing the whole cache now, though it's pretty inefficient
            getRuntime().getCacheMap().clear();
        }

    }

    public void defineMethod(String name, Callback method) {
        if (method.getClass().isAnonymousClass()) Thread.dumpStack();
        Visibility visibility = name.equals("initialize") ?
                Visibility.PRIVATE : Visibility.PUBLIC;
        addMethod(name, new FullFunctionCallbackMethod(this, method, visibility));
    }
    
    public void defineAnnotatedMethods(Class clazz, CallbackFactory callbackFactory) {
        Method[] methods = clazz.getDeclaredMethods();
        for (Method method: methods) {
            JRubyMethod jrubyMethod = method.getAnnotation(JRubyMethod.class);
            
            if (jrubyMethod == null) continue;
            
            // select current module or module's metaclass for singleton methods
            RubyModule module = this;
            if (jrubyMethod.singleton()) module = getMetaClass();

            if (jrubyMethod.optional() != 0) {
                if (Modifier.isStatic(method.getModifiers())) {
                    module.defineFastMethod(jrubyMethod.name(), callbackFactory.getFastOptSingletonMethod(method.getName()));
                } else {
                    module.defineFastMethod(jrubyMethod.name(), callbackFactory.getFastOptMethod(method.getName()));
                }
            } else {
                switch (jrubyMethod.required()) {
                case 0:
                    if (Modifier.isStatic(method.getModifiers())) {
                        module.defineFastMethod(jrubyMethod.name(), callbackFactory.getFastSingletonMethod(method.getName()));
                    } else {
                        module.defineFastMethod(jrubyMethod.name(), callbackFactory.getFastMethod(method.getName()));
                    }
                    break;
                case 1:
                    if (Modifier.isStatic(method.getModifiers())) {
                        module.defineFastMethod(jrubyMethod.name(), callbackFactory.getFastSingletonMethod(method.getName(), IRubyObject.class));
                    } else {
                        module.defineFastMethod(jrubyMethod.name(), callbackFactory.getFastMethod(method.getName(), IRubyObject.class));
                    }
                    break;
                case 2:
                    if (Modifier.isStatic(method.getModifiers())) {
                        module.defineFastMethod(jrubyMethod.name(), callbackFactory.getFastSingletonMethod(method.getName(), IRubyObject.class, IRubyObject.class));
                    } else {
                        module.defineFastMethod(jrubyMethod.name(), callbackFactory.getFastMethod(method.getName(), IRubyObject.class, IRubyObject.class));
                    }
                    break;
                case 3:
                    if (Modifier.isStatic(method.getModifiers())) {
                        module.defineFastMethod(jrubyMethod.name(), callbackFactory.getFastSingletonMethod(method.getName(), IRubyObject.class, IRubyObject.class, IRubyObject.class));
                    } else {
                        module.defineFastMethod(jrubyMethod.name(), callbackFactory.getFastMethod(method.getName(), IRubyObject.class, IRubyObject.class, IRubyObject.class));
                    }
                    break;
                default:
                    throw new RuntimeException("Invalid number of required args for annotated method");
                }
            }
            
            if (jrubyMethod.alias() != "") {
                module.defineAlias(jrubyMethod.alias(), jrubyMethod.name());
            }
        }
    }

    public void defineFastMethod(String name, Callback method) {
        Visibility visibility = name.equals("initialize") ?
                Visibility.PRIVATE : Visibility.PUBLIC;
        addMethod(name, new SimpleCallbackMethod(this, method, visibility));
    }

    public void defineFastMethod(String name, Callback method, Visibility visibility) {
        addMethod(name, new SimpleCallbackMethod(this, method, visibility));
    }

    public void definePrivateMethod(String name, Callback method) {
        addMethod(name, new FullFunctionCallbackMethod(this, method, Visibility.PRIVATE));
    }

    public void defineFastPrivateMethod(String name, Callback method) {
        addMethod(name, new SimpleCallbackMethod(this, method, Visibility.PRIVATE));
    }

    public void defineFastProtectedMethod(String name, Callback method) {
        addMethod(name, new SimpleCallbackMethod(this, method, Visibility.PROTECTED));
    }

    public void undefineMethod(String name) {
        addMethod(name, UndefinedMethod.getInstance());
    }

    /** rb_undef
     *
     */
    public void undef(String name) {
        Ruby runtime = getRuntime();
        if (this == runtime.getObject()) {
            runtime.secure(4);
        }
        if (runtime.getSafeLevel() >= 4 && !isTaint()) {
            throw new SecurityException("Insecure: can't undef");
        }
        testFrozen("module");
        if (name.equals("__id__") || name.equals("__send__")) {
            getRuntime().getWarnings().warn("undefining `"+ name +"' may cause serious problem");
        }
        DynamicMethod method = searchMethod(name);
        if (method.isUndefined()) {
            String s0 = " class";
            RubyModule c = this;

            if (c.isSingleton()) {
                IRubyObject obj = fastGetInternalVariable("__attached__");

                if (obj != null && obj instanceof RubyModule) {
                    c = (RubyModule) obj;
                    s0 = "";
                }
            } else if (c.isModule()) {
                s0 = " module";
            }

            throw getRuntime().newNameError("Undefined method " + name + " for" + s0 + " '" + c.getName() + "'", name);
        }
        addMethod(name, UndefinedMethod.getInstance());
        
        if(isSingleton()){
            IRubyObject singleton = fastGetInternalVariable("__attached__"); 
            singleton.callMethod(runtime.getCurrentContext(), "singleton_method_undefined", getRuntime().newSymbol(name));
        }else{
            callMethod(runtime.getCurrentContext(), "method_undefined", getRuntime().newSymbol(name));
    }
    }
    
    public IRubyObject include_p(IRubyObject arg) {
        if (!((arg instanceof RubyModule) && ((RubyModule)arg).isModule())){
            throw getRuntime().newTypeError(arg, getRuntime().getClass("Module"));
        }
        
        for (RubyModule p = this; p != null; p = p.getSuperClass()) {
            if ((p instanceof IncludedModuleWrapper) && ((IncludedModuleWrapper) p).getNonIncludedClass() == arg) {
                return getRuntime().newBoolean(true);
            }
        }
        
        return getRuntime().newBoolean(false);
    }

    // TODO: Consider a better way of synchronizing 
    public void addMethod(String name, DynamicMethod method) {
        if (this == getRuntime().getObject()) {
            getRuntime().secure(4);
        }

        if (getRuntime().getSafeLevel() >= 4 && !isTaint()) {
            throw getRuntime().newSecurityError("Insecure: can't define method");
        }
        testFrozen("class/module");

        // We can safely reference methods here instead of doing getMethods() since if we
        // are adding we are not using a IncludedModuleWrapper.
        synchronized(getMethods()) {
            // If we add a method which already is cached in this class, then we should update the 
            // cachemap so it stays up to date.
            DynamicMethod existingMethod = (DynamicMethod) getMethods().remove(name);
            if (existingMethod != null) {
                getRuntime().getCacheMap().remove(name, existingMethod);
            }
            getRuntime().getMethodCache().removeMethod(name);
            putMethod(name, method);
        }
    }

    public void removeCachedMethod(String name) {
        getMethods().remove(name);
    }

    public void removeMethod(String name) {
        if (this == getRuntime().getObject()) {
            getRuntime().secure(4);
        }
        if (getRuntime().getSafeLevel() >= 4 && !isTaint()) {
            throw getRuntime().newSecurityError("Insecure: can't remove method");
        }
        testFrozen("class/module");

        // We can safely reference methods here instead of doing getMethods() since if we
        // are adding we are not using a IncludedModuleWrapper.
        synchronized(getMethods()) {
            DynamicMethod method = (DynamicMethod) getMethods().remove(name);
            if (method == null) {
                throw getRuntime().newNameError("method '" + name + "' not defined in " + getName(), name);
            }
            
            getRuntime().getMethodCache().removeMethod(name);

            getRuntime().getCacheMap().remove(name, method);
        }
        
        if(isSingleton()){
            IRubyObject singleton = fastGetInternalVariable("__attached__"); 
            singleton.callMethod(getRuntime().getCurrentContext(), "singleton_method_removed", getRuntime().newSymbol(name));
        }else{
            callMethod(getRuntime().getCurrentContext(), "method_removed", getRuntime().newSymbol(name));
    }
    }

    /**
     * Search through this module and supermodules for method definitions. Cache superclass definitions in this class.
     * 
     * @param name The name of the method to search for
     * @return The method, or UndefinedMethod if not found
     */
    public DynamicMethod searchMethod(String name) {
        MethodCache cache = getRuntime().getMethodCache();
        MethodCache.CacheEntry entry = cache.getMethod(this, name);
        if (entry.klass == this && name.equals(entry.mid)) {
            return entry.method;
        }
        
        for (RubyModule searchModule = this; searchModule != null; searchModule = searchModule.getSuperClass()) {
            // included modules use delegates methods for we need to synchronize on result of getMethods
            synchronized(searchModule.getMethods()) {
                // See if current class has method or if it has been cached here already
                DynamicMethod method = (DynamicMethod) searchModule.getMethods().get(name);
                
                if (method != null) {
                    cache.putMethod(this, name, method);
                    /*
                    // TO BE REMOVED
                    if (searchModule != this) {
                        addCachedMethod(name, method);
                    }
                    */

                    return method;
                }
            }
        }

        return UndefinedMethod.getInstance();
    }

    /**
     * Search through this module and supermodules for method definitions. Cache superclass definitions in this class.
     * 
     * @param name The name of the method to search for
     * @return The method, or UndefinedMethod if not found
     */
    public DynamicMethod retrieveMethod(String name) {
        return (DynamicMethod)getMethods().get(name);
    }

    /**
     * Search through this module and supermodules for method definitions. Cache superclass definitions in this class.
     * 
     * @param name The name of the method to search for
     * @return The method, or UndefinedMethod if not found
     */
    public RubyModule findImplementer(RubyModule clazz) {
        for (RubyModule searchModule = this; searchModule != null; searchModule = searchModule.getSuperClass()) {
            if (searchModule.isSame(clazz)) {
                return searchModule;
            }
        }

        return null;
    }

    public void addModuleFunction(String name, DynamicMethod method) {
        addMethod(name, method);
        getSingletonClass().addMethod(name, method);
    }

    /** rb_define_module_function
     *
     */
    public void defineModuleFunction(String name, Callback method) {
        definePrivateMethod(name, method);
        getSingletonClass().defineMethod(name, method);
    }

    /** rb_define_module_function
     *
     */
    public void definePublicModuleFunction(String name, Callback method) {
        defineMethod(name, method);
        getSingletonClass().defineMethod(name, method);
    }

    /** rb_define_module_function
     *
     */
    public void defineFastModuleFunction(String name, Callback method) {
        defineFastPrivateMethod(name, method);
        getSingletonClass().defineFastMethod(name, method);
    }

    /** rb_define_module_function
     *
     */
    public void defineFastPublicModuleFunction(String name, Callback method) {
        defineFastMethod(name, method);
        getSingletonClass().defineFastMethod(name, method);
    }
    
    /** rb_alias
    *
    */
   public synchronized void defineAlias(String name, String oldName) {
       testFrozen("module");
       if (oldName.equals(name)) {
           return;
       }
       if (this == getRuntime().getObject()) {
           getRuntime().secure(4);
       }
       DynamicMethod method = searchMethod(oldName);
       if (method.isUndefined()) {
           if (isModule()) {
               method = getRuntime().getObject().searchMethod(oldName);
           }

           if (method.isUndefined()) {
               throw getRuntime().newNameError("undefined method `" + oldName + "' for " +
                       (isModule() ? "module" : "class") + " `" + getName() + "'", oldName);
           }
       }
       getRuntime().getMethodCache().removeMethod(name);
       getRuntime().getCacheMap().remove(name, method);
       putMethod(name, new AliasMethod(this, method, oldName));
   }


    //
    // CONSTANTS
    //
    
   
    public boolean hasLocalConstant(final String name) {
        return getVariableStore().hasConstant(name);
    }
    
    public boolean fastHasLocalConstant(final String internedName) {
        return getVariableStore().fastHasConstant(internedName);
    }
    
    // returns the stored value without processing undefs (autoloads)
    public IRubyObject getLocalConstant(final String name) {
        return getVariableStore().getConstant(name);
    }
    
    // returns the stored value without processing undefs (autoloads)
    public IRubyObject fastGetLocalConstant(final String internedName) {
        return getVariableStore().fastGetConstant(internedName);
    }
    
    // removes and returns the stored value without processing undefs (autoloads)
    public IRubyObject removeLocalConstant(final String name) {
        return getVariableStore().removeConstant(name);
    }

    public List<Variable<IRubyObject>> getLocalConstantList() {
        return getVariableStore().getConstantList();
    }
    public List<String> getLocalConstantNameList() {
        return getVariableStore().getConstantNameList();
    }
    
    // not actually called anywhere (all known uses call the fast version)
    public IRubyObject searchConstantExclusive(final String name) {
        return fastSearchConstantExclusive(name.intern());
    }
    
    // replaces getConstantFrom (called from evaluator and thread context)
    public IRubyObject fastSearchConstantExclusive(final String internedName) {
        final RubyClass objectClass = getRuntime().getObject();
        final IRubyObject undef = getRuntime().getUndef();
        IRubyObject value;

        RubyModule p = this;
        
        while (p != null) {
            if ((value = p.fastGetLocalConstant(internedName)) != null) {
                if (value != undef) {
                    if (p == objectClass && this != objectClass) {
                        getRuntime().getWarnings().warn("toplevel constant " + internedName +
                                " referenced by " + getName() + "::" + internedName);
                    }
                    return value;
                }
                p.removeLocalConstant(internedName);
                if (getRuntime().getLoadService().autoload(
                        p.getName() + "::" + internedName) == null) {
                    break;
                }
                continue;
            }
            p = p.getSuperClass();
        }

        return callMethod(getRuntime().getCurrentContext(),
                "const_missing", RubySymbol.newSymbol(getRuntime(), internedName));
    }

    public IRubyObject searchConstant(final String name) {
        // FIXME: lazy implementation
        return fastSearchConstant(name.intern());
    }

    public IRubyObject fastSearchConstant(final String internedName) {
        final IRubyObject undef = getRuntime().getUndef();
        boolean retryForModule = false;
        IRubyObject value;
        RubyModule p = this;

        retry: while (true) {
            while (p != null) {
                if ((value = p.fastGetLocalConstant(internedName)) != null) {
                    if (value != undef) {
                        return value;
                    }
                    p.removeLocalConstant(internedName);
                    if (getRuntime().getLoadService().autoload(
                            p.getName() + "::" + internedName) == null) {
                        break;
                    }
                    continue;
                }
                p = p.getSuperClass();
            }

            if (!retryForModule && !isClass()) {
                retryForModule = true;
                p = getRuntime().getObject();
                continue retry;
            }

            break;
        }

        return callMethod(getRuntime().getCurrentContext(),
                "const_missing", RubySymbol.newSymbol(getRuntime(), internedName));
    }

    @Deprecated // FIXME: remove (just keeping until I've verified the new code works)
    private IRubyObject getConstantInner(String name, final boolean exclude) {
        IRubyObject objectClass = getRuntime().getObject();
        IRubyObject undef = getRuntime().getUndef();
        boolean retryForModule = false;
        RubyModule p = this;

        retry: while (true) {
            while (p != null) {
                IRubyObject constant = p.getInstanceVariable(name);

                if (constant == undef) {
                    p.removeInstanceVariable(name);
                    if (getRuntime().getLoadService().autoload(p.getName() + "::" + name) == null) break;
                    continue;
                }
                if (constant != null) {
                    if (exclude && p == objectClass && this != objectClass) {
                        getRuntime().getWarnings().warn("toplevel constant " + name +
                                " referenced by " + getName() + "::" + name);
                    }

                    return constant;
                }
                p = p.getSuperClass();
            }

            if (!exclude && !retryForModule && getClass().equals(RubyModule.class)) {
                retryForModule = true;
                p = getRuntime().getObject();
                continue retry;
            }

            break;
        }

        return callMethod(getRuntime().getCurrentContext(), "const_missing", RubySymbol.newSymbol(getRuntime(), name));
    }

    /**
     * Retrieve the named constant, invoking 'const_missing' should that be appropriate.
     * 
     * @param name The constant to retrieve
     * @return The value for the constant, or null if not found
     * @deprecated superseded by {@link #searchConstant(String)}
     */
    public IRubyObject getConstant(String name) {
        return searchConstant(name);
    }

    /**
     * @deprecated superseded by {@link #searchConstantExclusive(String)}
     */
    public IRubyObject getConstantFrom(String name) {
        return searchConstantExclusive(name);
    }

    public IRubyObject getConstantAt(final String name) {
        final IRubyObject value;
        if ((value = getVariableStore().getConstant(name)) != getRuntime().getUndef()) {
            return value;
        }
        getVariableStore().removeConstant(name);
        return getRuntime().getLoadService().autoload(getName() + "::" + name);
    }

    public IRubyObject fastGetConstantAt(final String internedName) {
        final IRubyObject value;
        if ((value = getVariableStore().fastGetConstant(internedName)) != getRuntime().getUndef()) {
            return value;
        }
        getVariableStore().removeConstant(internedName);
        return getRuntime().getLoadService().autoload(getName() + "::" + internedName);
    }

    /**
     * Set the named constant on this module. Also, if the value provided is another Module and
     * that module has not yet been named, assign it the specified name.
     * 
     * @param name The name to assign
     * @param value The value to assign to it; if an unnamed Module, also set its basename to name
     * @return The result of setting the variable.
     * @see RubyObject#setInstanceVariable(String, IRubyObject, String, String)
     */
    public IRubyObject setConstant(final String name, final IRubyObject value) {
        // this might seem lazy, but since constants normally only get
        // set once, the name will have to be interned anyway. and most
        // code will call the fast version in the first place.
        return fastSetConstant(name.intern(), value);
    }
    
    public IRubyObject fastSetConstant(final String internedName, final IRubyObject value) {
        final VariableStore<IRubyObject> store = getVariableStore();
        final IRubyObject oldValue;
        if ((oldValue = store.fastGetConstant(internedName)) != null) {
            if (oldValue == getRuntime().getUndef()) {
                getRuntime().getLoadService().removeAutoLoadFor(getName() + "::" + internedName);
            } else {
                getRuntime().getWarnings().warn("already initialized constant " + internedName);
            }
        }

        store.fastSetConstant(internedName, value);

        // if adding a module under a constant name, set that module's basename to the constant name
        if (value instanceof RubyModule) {
            RubyModule module = (RubyModule)value;
            if (module.getBaseName() == null) {
                module.setBaseName(internedName);
                module.setParent(this);
            }
            /*
            module.setParent(this);
            */
        }
        return value;
    }
    
    private boolean hasConstantInHierarchy(final String name) {
        for (RubyModule p = this; p != null; p = p.getSuperClass()) {
            if (p.hasLocalConstant(name)) {
                return true;
            }
        }
        return false;
    }
    
    /** rb_define_const
     *
     */
    public void defineConstant(String name, IRubyObject value) {
        assert value != null;

        if (this == getRuntime().getClass("Class")) {
            getRuntime().secure(4);
        }

        if (!IdUtil.isValidConstantName(name)) {
            throw getRuntime().newNameError("bad constant name " + name, name);
        }

        setConstant(name, value);
    }


    /** rb_mod_const_get
    *
    */
    public IRubyObject const_get(IRubyObject symbol) {
        String name = symbol.asSymbol();

        if (!IdUtil.isValidConstantName(name)) {
            throw wrongConstantNameError(name);
        }

        return fastSearchConstant(name);
    }

    /** rb_mod_const_set
     *
     */
    public IRubyObject const_set(IRubyObject symbol, IRubyObject value) {
        String name = symbol.asSymbol();

        VariableStore.validateConstant(getRuntime(), name);

        return fastSetConstant(name, value);
    }

    /** rb_mod_const_defined
     *
     */
    public RubyBoolean const_defined(IRubyObject symbol) {
        return getRuntime().newBoolean(
                getVariableStore().fastValidatedHasConstant(symbol.asSymbol()));
    }

    public IRubyObject remove_const(final IRubyObject name) {
        final String id = name.asSymbol();
        final IRubyObject value;
        if ((value = getVariableStore().validatedRemoveConstant(id)) != null) {
            if (value != getRuntime().getUndef()) {
                return value;
            }
            getRuntime().getLoadService().removeAutoLoadFor(getName() + "::" + id);
            // FIXME: I'm not sure this is right, but the old code returned
            // the undef, which definitely isn't right...
            return getRuntime().getNil();
        }

        if (hasConstantInHierarchy(id)) {
            throw cannotRemoveError(id);
        }

        throw getRuntime().newNameError("constant " + id + " not defined for " + getName(), id);
    }

    private RaiseException wrongConstantNameError(String name) {
        return getRuntime().newNameError("wrong constant name " + name, name);
    }

    /**
     * Base implementation of Module#const_missing, throws NameError for specific missing constant.
     * 
     * @param name The constant name which was found to be missing
     * @return Nothing! Absolutely nothing! (though subclasses might choose to return something)
     */
    public IRubyObject const_missing(IRubyObject name, Block block) {
        /* Uninitialized constant */
        if (this != getRuntime().getObject()) {
            throw getRuntime().newNameError("uninitialized constant " + getName() + "::" + name.asSymbol(), "" + getName() + "::" + name.asSymbol());
        }

        throw getRuntime().newNameError("uninitialized constant " + name.asSymbol(), name.asSymbol());
    }

    public RubyClass defineOrGetClassUnder(final String name, final RubyClass superClazz) {
        // This method is intended only for defining new classes in Ruby code,
        // so it uses the allocator of the specified superclass or default to
        // the Object allocator. It should NOT be used to define classes that require a native allocator.
        IRubyObject type = getLocalConstant(name);
        
        if (type == null || (type instanceof RubyUndef)) {
            if (classProviders != null) {
                if ((type = searchClassProviders(name, superClazz)) != null) {
                    return (RubyClass)type;
                }
            }
            ObjectAllocator allocator = superClazz == null ? getRuntime().getObject().getAllocator() : superClazz.getAllocator();
            return getRuntime().defineClassUnder(name, superClazz, allocator, this);
        } 

        if (!(type instanceof RubyClass)) {
            throw getRuntime().newTypeError(name + " is not a class.");
        } else if (superClazz != null && ((RubyClass) type).getSuperClass().getRealClass() != superClazz) {
            throw getRuntime().newTypeError("superclass mismatch for class " + name);
        }

        return (RubyClass) type;
    }

    /** rb_define_class_under
     *
     */
    public RubyClass defineClassUnder(final String name, final RubyClass superClazz, final ObjectAllocator allocator) {
        IRubyObject type = getLocalConstant(name);

        if (type == null) {
            return getRuntime().defineClassUnder(name, superClazz, allocator, this);
        }

        if (!(type instanceof RubyClass)) {
            throw getRuntime().newTypeError(name + " is not a class.");
        } else if (((RubyClass) type).getSuperClass().getRealClass() != superClazz) {
            throw getRuntime().newNameError(name + " is already defined.", name);
        }

        return (RubyClass) type;
    }

    public RubyModule defineModuleUnder(String name) {
        IRubyObject type = getConstantAt(name);

        if (type == null) {
            return getRuntime().defineModuleUnder(name, this);
        }

        if (!(type instanceof RubyModule)) {
            throw getRuntime().newTypeError(name + " is not a module.");
        }

        return (RubyModule) type;
    }


    /** rb_mod_remove_cvar
     *
     */
    public IRubyObject removeCvar(final IRubyObject name) { // Wrong Parameter ?
        final String id = name.asSymbol();
        IRubyObject value = getVariableStore().validatedRemoveClassVariable(id);

        if (value != null) {
            return value;
        }

        if (fastIsClassVarDefined(id)) {
            throw cannotRemoveError(id);
        }

        throw getRuntime().newNameError("class variable " + id + " not defined for " + getName(), id);
    }

    private void addAccessor(String name, final boolean readable, final boolean writeable) {
        final Ruby runtime = getRuntime();
        final ThreadContext context = runtime.getCurrentContext();
        //final ThreadContext tc = runtime.getCurrentContext();

        // Check the visibility of the previous frame, which will be the frame in which the class is being eval'ed
        Visibility attributeScope = context.getCurrentVisibility();
        if (attributeScope.isPrivate()) {
            //FIXME warning
        } else if (attributeScope.isModuleFunction()) {
            attributeScope = Visibility.PRIVATE;
            // FIXME warning
        }
        final String variableName = ("@" + name).intern();
        if (readable) {
            defineFastMethod(name, new Callback() {
                public IRubyObject execute(final IRubyObject self, final IRubyObject[] args, final Block block) {
                    Arity.checkArgumentCount(getRuntime(), args, 0, 0);

                    final IRubyObject value;
                    if ((value = self.fastGetInstanceVariable(variableName)) != null) {
                        return value;
                    }
                    return runtime.getNil();
                }

                public Arity getArity() {
                    return Arity.noArguments();
                }
            });
            callMethod(context, "method_added", RubySymbol.newSymbol(getRuntime(), name));
        }
        if (writeable) {
            name = (name + "=").intern();
            defineFastMethod(name, new Callback() {
                public IRubyObject execute(final IRubyObject self, final IRubyObject[] args, final Block block) {
                    // ENEBO: Can anyone get args to be anything but length 1?
                    Arity.checkArgumentCount(getRuntime(), args, 1, 1);

                    return self.fastSetInstanceVariable(variableName, args[0]);
                }

                public Arity getArity() {
                    return Arity.singleArgument();
                }
            });
            callMethod(context, "method_added", RubySymbol.newSymbol(getRuntime(), name));
        }
    }

    /** set_method_visibility
     *
     */
    public void setMethodVisibility(IRubyObject[] methods, Visibility visibility) {
        if (getRuntime().getSafeLevel() >= 4 && !isTaint()) {
            throw getRuntime().newSecurityError("Insecure: can't change method visibility");
        }

        for (int i = 0; i < methods.length; i++) {
            exportMethod(methods[i].asSymbol(), visibility);
        }
    }

    /** rb_export_method
     *
     */
    public void exportMethod(String name, Visibility visibility) {
        if (this == getRuntime().getObject()) {
            getRuntime().secure(4);
        }

        DynamicMethod method = searchMethod(name);

        if (method.isUndefined()) {
            throw getRuntime().newNameError("undefined method '" + name + "' for " +
                                (isModule() ? "module" : "class") + " '" + getName() + "'", name);
        }

        if (method.getVisibility() != visibility) {
            if (this == method.getImplementationClass()) {
                method.setVisibility(visibility);
            } else {
                addMethod(name, new FullFunctionCallbackMethod(this, new Callback() {
                    public IRubyObject execute(IRubyObject self, IRubyObject[] args, Block block) {
                        ThreadContext tc = self.getRuntime().getCurrentContext();
                        return self.callSuper(tc, tc.getFrameArgs(), block);
                    }

                    public Arity getArity() {
                        return Arity.optional();
                    }
                }, visibility));
            }
        }
    }

    /**
     * MRI: rb_method_boundp
     *
     */
    public boolean isMethodBound(String name, boolean checkVisibility) {
        DynamicMethod method = searchMethod(name);
        if (!method.isUndefined()) {
            return !(checkVisibility && method.getVisibility().isPrivate());
        }
        return false;
    }

    public IRubyObject newMethod(IRubyObject receiver, String name, boolean bound) {
        DynamicMethod method = searchMethod(name);
        if (method.isUndefined()) {
            throw getRuntime().newNameError("undefined method `" + name +
                "' for class `" + this.getName() + "'", name);
        }

        RubyMethod newMethod = null;
        if (bound) {
            newMethod = RubyMethod.newMethod(method.getImplementationClass(), name, this, name, method, receiver);
        } else {
            newMethod = RubyUnboundMethod.newUnboundMethod(method.getImplementationClass(), name, this, name, method);
        }
        newMethod.infectBy(this);

        return newMethod;
    }

    // What is argument 1 for in this method? A Method or Proc object /OB
    public IRubyObject define_method(IRubyObject[] args, Block block) {
        if (args.length < 1 || args.length > 2) {
            throw getRuntime().newArgumentError("wrong # of arguments(" + args.length + " for 1)");
        }

        IRubyObject body;
        String name = args[0].asSymbol();
        DynamicMethod newMethod = null;
        ThreadContext tc = getRuntime().getCurrentContext();
        Visibility visibility = tc.getCurrentVisibility();

        if (visibility.isModuleFunction()) visibility = Visibility.PRIVATE;

        if (args.length == 1 || args[1].isKindOf(getRuntime().getClass("Proc"))) {
            // double-testing args.length here, but it avoids duplicating the proc-setup code in two places
            RubyProc proc = (args.length == 1) ? getRuntime().newProc(false, block) : (RubyProc)args[1];
            body = proc;

            proc.getBlock().isLambda = true;
            proc.getBlock().getFrame().setKlazz(this);
            proc.getBlock().getFrame().setName(name);

            newMethod = new ProcMethod(this, proc, visibility);
        } else if (args[1].isKindOf(getRuntime().getClass("Method"))) {
            RubyMethod method = (RubyMethod)args[1];
            body = method;

            newMethod = new MethodMethod(this, method.unbind(null), visibility);
        } else {
            throw getRuntime().newTypeError("wrong argument type " + args[0].getType().getName() + " (expected Proc/Method)");
        }

        addMethod(name, newMethod);

        RubySymbol symbol = RubySymbol.newSymbol(getRuntime(), name);
        ThreadContext context = getRuntime().getCurrentContext();

        if (tc.getPreviousVisibility().isModuleFunction()) {
            getSingletonClass().addMethod(name, new WrapperMethod(getSingletonClass(), newMethod, Visibility.PUBLIC));
        }

        if(isSingleton()){
            IRubyObject singleton = fastGetInternalVariable("__attached__"); 
            singleton.callMethod(context, "singleton_method_added", symbol);
        }else{
        callMethod(context, "method_added", symbol);
        }

        return body;
    }

    public IRubyObject executeUnder(Callback method, IRubyObject[] args, Block block) {
        ThreadContext context = getRuntime().getCurrentContext();

        context.preExecuteUnder(this, block);

        try {
            return method.execute(this, args, block);
        } finally {
            context.postExecuteUnder();
        }
    }

    // Methods of the Module Class (rb_mod_*):

    public static RubyModule newModule(Ruby runtime, String name) {
        return newModule(runtime, runtime.getClass("Module"), name, null);
    }

    public static RubyModule newModule(Ruby runtime, RubyClass type, String name) {
        return newModule(runtime, type, name, null);
    }

    public static RubyModule newModule(Ruby runtime, String name, RubyModule parent) {
        return newModule(runtime, runtime.getClass("Module"), name, parent);
    }

    public static RubyModule newModule(Ruby runtime, RubyClass type, String name, RubyModule parent) {
        RubyModule module = new RubyModule(runtime, type, null, parent, name);
        
        return module;
    }

    public RubyString name() {
        return getRuntime().newString(getBaseName() == null ? "" : getName());
    }

    /** rb_mod_class_variables
     *
     */
    public RubyArray class_variables() {
        // in theory we shouldn't have duplicates, but using Set to be sure (old logic
        // called #contains on array).
        // TODO: throw an exception if we find a duplicate?
        final Set<String> names = new HashSet<String>();

        for (RubyModule p = this; p != null; p = p.getSuperClass()) {
            for (String name : p.getLocalClassVariableNameList()) {
                names.add(name);
            }
        }
        
        final Ruby runtime = getRuntime();
        final RubyArray ary = runtime.newArray();
 
        for (String name : names) {
            ary.add(runtime.newString(name));
        }
        
        return ary;
    }

    /** rb_mod_cvar_get
    *
    */
    public IRubyObject class_variable_get(IRubyObject var) {
        String varName = var.asSymbol();

        if (!IdUtil.isValidClassVariableName(varName)) {
            throw getRuntime().newNameError("`" + varName + "' is not allowed as a class variable name", varName);
        }

        return fastGetClassVar(varName);
    }

    public IRubyObject class_variable_defined_p(final IRubyObject var) {
        final String name = var.asSymbol();

        if (!IdUtil.isValidClassVariableName(name)) {
            throw getRuntime().newNameError("`" + name + "' is not allowed as a class variable name", name);
        }

        final RubyModule module;

        if ((module = fastGetModuleWithClassVar(name)) != null) {
            return getRuntime().getTrue() ;
        }
        return getRuntime().getFalse();
    }

    /** rb_mod_cvar_set
    *
    */
    public IRubyObject class_variable_set(final IRubyObject var, final IRubyObject value) {
        final String varName = var.asSymbol();

        if (!IdUtil.isValidClassVariableName(varName)) {
            throw getRuntime().newNameError("`" + varName + "' is not allowed as a class variable name", varName);
        }

        return fastSetClassVar(varName, value);
    }

    protected IRubyObject cloneMethods(RubyModule clone) {
        RubyModule realType = this.getNonIncludedClass();
        for (Iterator iter = getMethods().entrySet().iterator(); iter.hasNext(); ) {
            Map.Entry entry = (Map.Entry) iter.next();
            DynamicMethod method = (DynamicMethod) entry.getValue();
            // Do not clone cached methods
            // FIXME: MRI copies all methods here
            if (method.getImplementationClass() == realType || method instanceof UndefinedMethod) {
                
                // A cloned method now belongs to a new class.  Set it.
                // TODO: Make DynamicMethod immutable
                DynamicMethod clonedMethod = method.dup();
                clonedMethod.setImplementationClass(clone);
                clone.putMethod(entry.getKey(), clonedMethod);
            }
        }

        return clone;
    }

    protected IRubyObject doClone() {
        return RubyModule.newModule(getRuntime(), null, parent);
    }

    /** rb_mod_init_copy
     * 
     */
    public IRubyObject initialize_copy(IRubyObject original) {
        assert original instanceof RubyModule;
        
        RubyModule originalModule = (RubyModule)original;
        
        super.initialize_copy(originalModule);
        
        if (!getMetaClass().isSingleton()) {
            setMetaClass(originalModule.getSingletonClassClone());
        }

        setSuperClass(originalModule.getSuperClass());
        
        if (originalModule.hasVariables()){
            syncVariables(originalModule.getVariableList());
        }
        
        // no __classpath__ and __classid__ stuff in JRuby here (yet?)        

        originalModule.cloneMethods(this);
        
        return this;        
    }

    /** rb_mod_included_modules
     *
     */
    public RubyArray included_modules() {
        RubyArray ary = getRuntime().newArray();

        for (RubyModule p = getSuperClass(); p != null; p = p.getSuperClass()) {
            if (p.isIncluded()) {
                ary.append(p.getNonIncludedClass());
            }
        }

        return ary;
    }

    /** rb_mod_ancestors
     *
     */
    public RubyArray ancestors() {
        RubyArray ary = getRuntime().newArray(getAncestorList());

        return ary;
    }

    public List getAncestorList() {
        ArrayList list = new ArrayList();

        for (RubyModule p = this; p != null; p = p.getSuperClass()) {
            if(!p.isSingleton()) {
                list.add(p.getNonIncludedClass());
            }
        }

        return list;
    }

    public boolean hasModuleInHierarchy(RubyModule type) {
        // XXX: This check previously used callMethod("==") to check for equality between classes
        // when scanning the hierarchy. However the == check may be safe; we should only ever have
        // one instance bound to a given type/constant. If it's found to be unsafe, examine ways
        // to avoid the == call.
        for (RubyModule p = this; p != null; p = p.getSuperClass()) {
            if (p.getNonIncludedClass() == type) return true;
        }

        return false;
    }

    public int hashCode() {
        return id;
    }

    public RubyFixnum hash() {
        return getRuntime().newFixnum(id);
    }

    /** rb_mod_to_s
     *
     */
    public IRubyObject to_s() {
        if(isSingleton()){            
            IRubyObject attached = fastGetInternalVariable("__attached__");
            StringBuffer buffer = new StringBuffer("#<Class:");
            if(attached instanceof RubyClass || attached instanceof RubyModule){
                buffer.append(attached.inspect());
            }else{
                buffer.append(attached.anyToString());
            }
            buffer.append(">");
            return getRuntime().newString(buffer.toString());
        }
        return getRuntime().newString(getName());
    }

    /** rb_mod_eqq
     *
     */
    public RubyBoolean op_eqq(IRubyObject obj) {
        return getRuntime().newBoolean(obj.isKindOf(this));
    }

    /** rb_mod_le
    *
    */
   public IRubyObject op_le(IRubyObject obj) {
       if (!(obj instanceof RubyModule)) {
           throw getRuntime().newTypeError("compared with non class/module");
       }

       if (isKindOfModule((RubyModule)obj)) {
           return getRuntime().getTrue();
       } else if (((RubyModule)obj).isKindOfModule(this)) {
           return getRuntime().getFalse();
       }

       return getRuntime().getNil();
   }

   /** rb_mod_lt
    *
    */
   public IRubyObject op_lt(IRubyObject obj) {
    return obj == this ? getRuntime().getFalse() : op_le(obj);
   }

   /** rb_mod_ge
    *
    */
   public IRubyObject op_ge(IRubyObject obj) {
       if (!(obj instanceof RubyModule)) {
           throw getRuntime().newTypeError("compared with non class/module");
       }

       return ((RubyModule) obj).op_le(this);
   }

   /** rb_mod_gt
    *
    */
   public IRubyObject op_gt(IRubyObject obj) {
       return this == obj ? getRuntime().getFalse() : op_ge(obj);
   }

   /** rb_mod_cmp
    *
    */
   public IRubyObject op_cmp(IRubyObject obj) {
       if (this == obj) {
           return getRuntime().newFixnum(0);
       }

       if (!(obj instanceof RubyModule)) {
           throw getRuntime().newTypeError(
               "<=> requires Class or Module (" + getMetaClass().getName() + " given)");
       }

       RubyModule module = (RubyModule)obj;

       if (module.isKindOfModule(this)) {
           return getRuntime().newFixnum(1);
       } else if (this.isKindOfModule(module)) {
           return getRuntime().newFixnum(-1);
       }

       return getRuntime().getNil();
   }

   public boolean isKindOfModule(RubyModule type) {
       for (RubyModule p = this; p != null; p = p.getSuperClass()) {
           if (p.isSame(type)) {
               return true;
           }
       }

       return false;
   }

   public boolean isSame(RubyModule module) {
       return this == module;
   }

    /** rb_mod_initialize
     *
     */
    public IRubyObject initialize(IRubyObject[] args, Block block) {
        if (block.isGiven()) {
            // class and module bodies default to public, so make the block's visibility public. JRUBY-1185.
            block.setVisibility(Visibility.PUBLIC);
            block.yield(getRuntime().getCurrentContext(), null, this, this, false);
        }
        
        return getRuntime().getNil();
    }

    /** rb_mod_attr
     *
     */
    public IRubyObject attr(IRubyObject[] args) {
        Arity.checkArgumentCount(getRuntime(), args, 1, 2);
        boolean writeable = args.length > 1 ? args[1].isTrue() : false;

        addAccessor(args[0].asSymbol(), true, writeable);

        return getRuntime().getNil();
    }

    /** rb_mod_attr_reader
     *
     */
    public IRubyObject attr_reader(IRubyObject[] args) {
        for (int i = 0; i < args.length; i++) {
            addAccessor(args[i].asSymbol(), true, false);
        }

        return getRuntime().getNil();
    }

    /** rb_mod_attr_writer
     *
     */
    public IRubyObject attr_writer(IRubyObject[] args) {
        for (int i = 0; i < args.length; i++) {
            addAccessor(args[i].asSymbol(), false, true);
        }

        return getRuntime().getNil();
    }

    /** rb_mod_attr_accessor
     *
     */
    public IRubyObject attr_accessor(IRubyObject[] args) {
        for (int i = 0; i < args.length; i++) {
            addAccessor(args[i].asSymbol(), true, true);
        }

        return getRuntime().getNil();
    }

    private RubyArray instance_methods(IRubyObject[] args, final Visibility visibility) {
        boolean includeSuper = args.length > 0 ? args[0].isTrue() : true;
        RubyArray ary = getRuntime().newArray();
        HashMap undefinedMethods = new HashMap();
        Set added = new HashSet();

        for (RubyModule type = this; type != null; type = type.getSuperClass()) {
            RubyModule realType = type.getNonIncludedClass();
            for (Iterator iter = type.getMethods().entrySet().iterator(); iter.hasNext();) {
                Map.Entry entry = (Map.Entry) iter.next();
                DynamicMethod method = (DynamicMethod) entry.getValue();
                String methodName = (String) entry.getKey();

                if (method.isUndefined()) {
                    undefinedMethods.put(methodName, Boolean.TRUE);
                    continue;
                }
                if (method.getImplementationClass() == realType &&
                    method.getVisibility().is(visibility) && undefinedMethods.get(methodName) == null) {

                    if (!added.contains(methodName)) {
                        ary.append(getRuntime().newString(methodName));
                        added.add(methodName);
                    }
                }
            }

            if (!includeSuper) {
                break;
            }
        }

        return ary;
    }

    public RubyArray instance_methods(IRubyObject[] args) {
        return instance_methods(args, Visibility.PUBLIC_PROTECTED);
    }

    public RubyArray public_instance_methods(IRubyObject[] args) {
        return instance_methods(args, Visibility.PUBLIC);
    }

    public IRubyObject instance_method(IRubyObject symbol) {
        return newMethod(null, symbol.asSymbol(), false);
    }

    /** rb_class_protected_instance_methods
     *
     */
    public RubyArray protected_instance_methods(IRubyObject[] args) {
        return instance_methods(args, Visibility.PROTECTED);
    }

    /** rb_class_private_instance_methods
     *
     */
    public RubyArray private_instance_methods(IRubyObject[] args) {
        return instance_methods(args, Visibility.PRIVATE);
    }

    /** rb_mod_constants
     *
     */
    public RubyArray constants() {
        final Ruby runtime = getRuntime();
        final RubyArray array = runtime.newArray();
        final RubyModule objectClass = runtime.getObject();

        if (getRuntime().getClass("Module") == this) {

            for (String name : objectClass.getLocalConstantNameList()) {
                array.add(runtime.newString(name));
            }

        } else if (objectClass == this) {

            for (String name : getLocalConstantNameList()) {
                array.add(runtime.newString(name));
            }

        } else {
            Set<String> names = new HashSet<String>();
            for (RubyModule p = this; p != null; p = p.getSuperClass()) {
                if (objectClass != p) {
                    for (String name : p.getLocalConstantNameList()) {
                        names.add(name);
                    }
                }
            }
            for (String name : names) {
                array.add(runtime.newString(name));
            }
        }

        return array;
    }

    /** rb_mod_remove_cvar
     *
     */
    public IRubyObject remove_class_variable(final IRubyObject name) {
        final String id = name.asSymbol();
        IRubyObject value = getVariableStore().validatedRemoveClassVariable(id);

        if (value != null) {
            return value;
        }

        if (fastIsClassVarDefined(id)) {
            throw cannotRemoveError(id);
        }

        throw getRuntime().newNameError("class variable " + id + " not defined for " + getName(), id);
    }

    private RaiseException cannotRemoveError(String id) {
        return getRuntime().newNameError("cannot remove " + id + " for " + getName(), id);
    }

    /** rb_mod_append_features
     *
     */
    public RubyModule append_features(IRubyObject module) {
        if (!(module instanceof RubyModule)) {
            // MRI error message says Class, even though Module is ok 
            throw getRuntime().newTypeError(module,getRuntime().getClass("Class"));
        }
        ((RubyModule) module).includeModule(this);
        return this;
    }

    /** rb_mod_extend_object
     *
     */
    public IRubyObject extend_object(IRubyObject obj) {
        obj.getSingletonClass().includeModule(this);
        return obj;
    }

    /** rb_mod_include
     *
     */
    public RubyModule include(IRubyObject[] modules) {
        ThreadContext context = getRuntime().getCurrentContext();
        // MRI checks all types first:
        for (int i = modules.length; --i >= 0; ) {
            IRubyObject obj;
            if (!(((obj = modules[i]) instanceof RubyModule) && ((RubyModule)obj).isModule())){
                throw getRuntime().newTypeError(obj,getRuntime().getClass("Module"));
            }
        }
        for (int i = modules.length - 1; i >= 0; i--) {
            modules[i].callMethod(context, "append_features", this);
            modules[i].callMethod(context, "included", this);
        }

        return this;
    }

    public IRubyObject included(IRubyObject other) {
        return getRuntime().getNil();
    }

    public IRubyObject extended(IRubyObject other, Block block) {
        return getRuntime().getNil();
    }

    private void setVisibility(IRubyObject[] args, Visibility visibility) {
        if (getRuntime().getSafeLevel() >= 4 && !isTaint()) {
            throw getRuntime().newSecurityError("Insecure: can't change method visibility");
        }

        if (args.length == 0) {
            // Note: we change current frames visibility here because the methods which call
            // this method are all "fast" (e.g. they do not created their own frame).
            getRuntime().getCurrentContext().setCurrentVisibility(visibility);
        } else {
            setMethodVisibility(args, visibility);
        }
    }

    /** rb_mod_public
     *
     */
    public RubyModule rbPublic(IRubyObject[] args) {
        setVisibility(args, Visibility.PUBLIC);
        return this;
    }

    /** rb_mod_protected
     *
     */
    public RubyModule rbProtected(IRubyObject[] args) {
        setVisibility(args, Visibility.PROTECTED);
        return this;
    }

    /** rb_mod_private
     *
     */
    public RubyModule rbPrivate(IRubyObject[] args) {
        setVisibility(args, Visibility.PRIVATE);
        return this;
    }

    /** rb_mod_modfunc
     *
     */
    public RubyModule module_function(IRubyObject[] args) {
        if (getRuntime().getSafeLevel() >= 4 && !isTaint()) {
            throw getRuntime().newSecurityError("Insecure: can't change method visibility");
        }

        ThreadContext context = getRuntime().getCurrentContext();

        if (args.length == 0) {
            context.setCurrentVisibility(Visibility.MODULE_FUNCTION);
        } else {
            setMethodVisibility(args, Visibility.PRIVATE);

            for (int i = 0; i < args.length; i++) {
                String name = args[i].asSymbol();
                DynamicMethod method = searchMethod(name);
                assert !method.isUndefined() : "undefined method '" + name + "'";
                getSingletonClass().addMethod(name, new WrapperMethod(getSingletonClass(), method, Visibility.PUBLIC));
                callMethod(context, "singleton_method_added", RubySymbol.newSymbol(getRuntime(), name));
            }
        }
        return this;
    }

    public IRubyObject method_added(IRubyObject nothing, Block block) {
        return getRuntime().getNil();
    }

    public IRubyObject method_removed(IRubyObject nothing, Block block) {
        return getRuntime().getNil();
    }

    public IRubyObject method_undefined(IRubyObject nothing, Block block) {
        return getRuntime().getNil();
    }
    
    public RubyBoolean method_defined(IRubyObject symbol) {
        return isMethodBound(symbol.asSymbol(), true) ? getRuntime().getTrue() : getRuntime().getFalse();
    }

    public IRubyObject public_method_defined(IRubyObject symbol) {
	    DynamicMethod method = searchMethod(symbol.asSymbol());
	    
		return getRuntime().newBoolean(!method.isUndefined() && method.getVisibility().isPublic());
    }

    public IRubyObject protected_method_defined(IRubyObject symbol) {
	    DynamicMethod method = searchMethod(symbol.asSymbol());
	    
		return getRuntime().newBoolean(!method.isUndefined() && method.getVisibility().isProtected());
    }
	
    public IRubyObject private_method_defined(IRubyObject symbol) {
	    DynamicMethod method = searchMethod(symbol.asSymbol());
	    
		return getRuntime().newBoolean(!method.isUndefined() && method.getVisibility().isPrivate());
    }

    public RubyModule public_class_method(IRubyObject[] args) {
        getMetaClass().setMethodVisibility(args, Visibility.PUBLIC);
        return this;
    }

    public RubyModule private_class_method(IRubyObject[] args) {
        getMetaClass().setMethodVisibility(args, Visibility.PRIVATE);
        return this;
    }

    public RubyModule alias_method(IRubyObject newId, IRubyObject oldId) {
        defineAlias(newId.asSymbol(), oldId.asSymbol());
        return this;
    }

    public RubyModule undef_method(IRubyObject[] args) {
        for (int i=0; i<args.length; i++) {
            undef(args[i].asSymbol());
        }
        return this;
    }

    public IRubyObject module_eval(IRubyObject[] args, Block block) {
        return specificEval(this, args, block);
    }

    public RubyModule remove_method(IRubyObject[] args) {
        for(int i=0;i<args.length;i++) {
            removeMethod(args[i].asSymbol());
        }
        return this;
    }

    public static void marshalTo(RubyModule module, MarshalStream output) throws java.io.IOException {
        output.writeString(module.name().toString());
    }

    public static RubyModule unmarshalFrom(UnmarshalStream input) throws java.io.IOException {
        String name = RubyString.byteListToString(input.unmarshalString());
        Ruby runtime = input.getRuntime();
        RubyModule result = runtime.getClassFromPath(name);
        if (result == null) {
            throw runtime.newNameError("uninitialized constant " + name, name);
        }
        input.registerLinkTarget(result);
        return result;
    }

    /* Module class methods */
    
    /** 
     * Return an array of nested modules or classes.
     */
    public static RubyArray nesting(IRubyObject recv, Block block) {
        Ruby runtime = recv.getRuntime();
        RubyModule object = runtime.getObject();
        StaticScope scope = runtime.getCurrentContext().getCurrentScope().getStaticScope();
        RubyArray result = runtime.newArray();
        
        for (StaticScope current = scope; current.getModule() != object; current = current.getPreviousCRefScope()) {
            result.append(current.getModule());
        }
        
        return result;
    }
}
