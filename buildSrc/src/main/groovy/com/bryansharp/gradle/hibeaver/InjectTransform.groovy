package com.bryansharp.gradle.hibeaver

import com.android.build.api.transform.*
import com.android.build.gradle.internal.pipeline.TransformManager
import com.bryansharp.gradle.hibeaver.utils.*
import groovy.io.FileType
import jdk.internal.jline.internal.Nullable
import org.apache.commons.io.FileUtils
import org.apache.commons.io.IOUtils
import org.gradle.internal.impldep.org.eclipse.jgit.annotations.NonNull

import java.util.jar.JarEntry
import java.util.jar.JarFile

/**
 * Created by bryansharp(bsp0911932@163.com) on 2016/5/7.
 *
 * @author bryansharp
 *         Project: FirstGradle
 *         introduction:
 */
public class InjectTransform extends Transform {

    @Override
    String getName() {
        return "HiBeaver"
    }

    @Override
    Set<QualifiedContent.ContentType> getInputTypes() {
        return TransformManager.CONTENT_CLASS
    }

    @Override
    Set<QualifiedContent.Scope> getScopes() {
        if(DataHelper.ext.projectType == DataHelper.TYPE_APP){
            return TransformManager.SCOPE_FULL_PROJECT
        }else if (DataHelper.ext.projectType == DataHelper.TYPE_LIB){
            return TransformManager.SCOPE_FULL_LIBRARY
        }
        return TransformManager.SCOPE_FULL_PROJECT
    }

    @Override
    boolean isIncremental() {
        return false
    }

    @Override
    public void transform(
            @NonNull Context context,
            @NonNull Collection<TransformInput> inputs,
            @NonNull Collection<TransformInput> referencedInputs,
            @Nullable TransformOutputProvider outputProvider,
            boolean isIncremental) throws IOException, TransformException, InterruptedException {
        Log.info "==============hiBeaver ${Util.getHiBeaver().hiBeaverModifyName + ' '}transform enter=============="
//        String flavorAndBuildType = context.name.split("For")[1]
//        Log.info("flavorAndBuildType ${flavorAndBuildType}")
        Map<String, Object> modifyMatchMaps = Util.getHiBeaver().modifyMatchMaps;
        Util.initTargetClasses(modifyMatchMaps)
        /**
         * ?????????????????????classPaths,????????????
         */
        def classPaths = []
        String buildTypes
        String productFlavors
        inputs.each { TransformInput input ->
            input.directoryInputs.each { DirectoryInput directoryInput ->
                classPaths.add(directoryInput.file.absolutePath)
                buildTypes = directoryInput.file.name
                productFlavors = directoryInput.file.parentFile.name
                Log.info("???????????????class????????????${directoryInput.file.absolutePath}");
            }
            Log.info('===============================')
            input.jarInputs.each { JarInput jarInput ->
                classPaths.add(jarInput.file.absolutePath)
                Log.info("???????????????jar??????${jarInput.file.absolutePath}");
            }
        }

        def paths = [Util.getExtension().bootClasspath.get(0).absolutePath/*, injectClassPath*/]
        paths.addAll(classPaths)
        /**??????????????????*/
        inputs.each { TransformInput input ->
            /**
             * ??????jar
             * JarInput???DirectoryInput????????????????????????QualifiedContent????????????
             * ?????????scope???????????????????????????QualifiedContent.Scope???????????????Input?????????????????????????????????
             * @see QualifiedContent.Scope
             */
            input.jarInputs.each { JarInput jarInput ->
                String destName = jarInput.file.name;
                /** ?????????????????????,??????????????????,?????????*/
                def hexName = DigestUtils.md5Hex(jarInput.file.absolutePath).substring(0, 8);
                if (destName.endsWith(".jar")) {
                    destName = destName.substring(0, destName.length() - 4);
                }
                /** ??????????????????*/
                File dest = outputProvider.getContentLocation(destName + "_" + hexName, jarInput.contentTypes, jarInput.scopes, Format.JAR);
                def modifiedJar = null;
                if (isJarNeedModify(jarInput.file)) {
                    modifiedJar = modifyJarFile(jarInput.file, context.getTemporaryDir());
                }
                if (modifiedJar == null) {
                    modifiedJar = jarInput.file;
                } else {
                    saveModifiedJarForCheck(modifiedJar);
                }
                FileUtils.copyFile(modifiedJar, dest);
            }
            /**
             * ????????????
             */
            input.directoryInputs.each { DirectoryInput directoryInput ->
                File dest = outputProvider.getContentLocation(directoryInput.name, directoryInput.contentTypes, directoryInput.scopes, Format.DIRECTORY);
//                Log.info("dest dir  ${dest.absolutePath}")
                File dir = directoryInput.file
                if (dir) {
                    HashMap<String, File> modifyMap = new HashMap<>();
                    dir.traverse(type: FileType.FILES, nameFilter: ~/.*\.class/) {
                        File classFile ->
                            File modified = modifyClassFile(dir, classFile, context.getTemporaryDir());
                            if (modified != null) {
                                //key???????????????
                                modifyMap.put(classFile.absolutePath.replace(dir.absolutePath, ""), modified);
                            }
                    }
                    FileUtils.copyDirectory(directoryInput.file, dest);
                    modifyMap.entrySet().each {
                        Map.Entry<String, File> en ->
                            File target = new File(dest.absolutePath + en.getKey());
                            Log.info(target.getAbsolutePath());
                            if (target.exists()) {
                                target.delete();
                            }
                            FileUtils.copyFile(en.getValue(), target);
                            saveModifiedJarForCheck(en.getValue());
                            en.getValue().delete();
                    }
                }
            }
        }
    }


    private static void saveModifiedJarForCheck(File optJar) {
        File dir = DataHelper.ext.hiBeaverDir;
        File checkJarFile = new File(dir, optJar.getName());
        if (checkJarFile.exists()) {
            checkJarFile.delete();
        }
        FileUtils.copyFile(optJar, checkJarFile);
    }

    /**
     * ????????????
     * @param buildDir ????????????build class??????,???????????????????????????class?????????
     * @param lib ?????????hackdex?????????,??????AntilazyLoad??????class???????????????
     */
    public static File modifyJarFile(File jarFile, File tempDir) {
        if (jarFile) {
            Map<String, Object> modifyMatchMaps = Util.getHiBeaver().modifyMatchMaps
            return ModifyFiles.modifyJar(jarFile, modifyMatchMaps, tempDir, true)

        }
        return null;
    }


    public static File modifyClassFile(File dir, File classFile, File tempDir) {
        File modified;
        try {
            String className = Util.path2Classname(classFile.absolutePath.replace(dir.absolutePath + File.separator, ""));
            Map<String, Object> modifyMatchMaps = Util.getHiBeaver().modifyMatchMaps
            byte[] sourceClassBytes = IOUtils.toByteArray(new FileInputStream(classFile));
            String key = Util.shouldModifyClass(className)
            if (key != null) {
                byte[] modifiedClassBytes = ModifyClassUtil.modifyClasses(className, sourceClassBytes, modifyMatchMaps.get(key));
                if (modifiedClassBytes) {
                    modified = new File(tempDir, className.replace('.', '') + '.class')
                    if (modified.exists()) {
                        modified.delete();
                    }
                    modified.createNewFile()
                    new FileOutputStream(modified).write(modifiedClassBytes)
                }
            }
        } catch (Exception e) {
        }
        return modified;

    }
    /**
     * ???jar????????????????????????????????????
     * @param jarFile
     * @return
     */
    public static boolean isJarNeedModify(File jarFile) {
        boolean modified = false;
        if (Util.isTargetClassesNotEmpty()) {
            if (jarFile) {
                /**
                 * ?????????jar
                 */
                def file = new JarFile(jarFile);
                Enumeration enumeration = file.entries();
                while (enumeration.hasMoreElements()) {
                    JarEntry jarEntry = (JarEntry) enumeration.nextElement();
                    String entryName = jarEntry.getName();
                    String className
                    if (entryName.endsWith(".class")) {
                        className = entryName.replace("/", ".").replace(".class", "")
                        if (Util.shouldModifyClass(className) != null) {
                            modified = true;
                        }
                    }
                }
                file.close();
            }
        }
        return modified;
    }

    private static void writeStreamWithBuffer(InputStream inputStream, OutputStream out) {
        byte[] buffer = new byte[1024];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
    }
}
