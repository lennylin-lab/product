package com.product.identity.common.utils.file;

import com.product.identity.common.config.ProductConfig;
import com.product.identity.common.constant.Constants;
import com.product.identity.common.exception.file.FileNameLengthLimitExceededException;
import com.product.identity.common.exception.file.InvalidExtensionException;
import com.product.identity.common.utils.DateUtils;
import com.product.identity.common.utils.StringUtils;
import com.product.identity.common.utils.uuid.Seq;
import com.product.identity.common.exception.file.FileSizeLimitExceededException;
import org.apache.commons.io.FilenameUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Objects;

/// 文件上传工具类
/// 提供完整的文件上传功能，包括：
/// - 文件大小限制检查
/// - 文件名长度限制检查
/// - 文件类型白名单验证
/// - 自动生成唯一文件名
/// - 目录自动创建
/// - 文件存储路径管理
///
/// @author fast
public class FileUploadUtils
{
    /**
     * 默认最大文件大小 50M
     * 用于限制上传文件的最大体积，防止存储空间被恶意占用
     */
    public static final long DEFAULT_MAX_SIZE = 50 * 1024 * 1024L;

    /**
     * 默认的文件名最大长度 100
     * 防止文件名过长导致的文件系统问题
     */
    public static final int DEFAULT_FILE_NAME_LENGTH = 100;

    /**
     * 默认上传的根目录
     * 从ProductConfig配置中获取，通常为项目运行环境的profile目录
     */
    private static String defaultBaseDir = ProductConfig.getProfile();

    /**
     * 设置默认上传基目录
     *
     * @param defaultBaseDir 基础上传目录路径
     */
    public static void setDefaultBaseDir(String defaultBaseDir)
    {
        FileUploadUtils.defaultBaseDir = defaultBaseDir;
    }

    /**
     * 获取默认上传基目录
     *
     * @return 当前配置的基础上传目录
     */
    public static String getDefaultBaseDir()
    {
        return defaultBaseDir;
    }

    /**
     * 以默认配置进行文件上传
     * 使用默认的上传目录和允许的文件类型进行文件上传
     *
     * @param file 上传的MultipartFile对象
     * @return 上传成功后的相对文件路径
     * @throws IOException 文件上传过程中的IO异常
     */
    public static final String upload(MultipartFile file) throws IOException
    {
        try
        {
            return upload(getDefaultBaseDir(), file, MimeTypeUtils.DEFAULT_ALLOWED_EXTENSION);
        }
        catch (Exception e)
        {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * 根据指定目录上传文件
     * 使用指定目录和默认允许的文件类型进行上传
     *
     * @param baseDir 相对应用的基目录路径
     * @param file 上传的MultipartFile对象
     * @return 上传成功后的相对文件路径
     * @throws IOException 文件上传过程中的IO异常
     */
    public static final String upload(String baseDir, MultipartFile file) throws IOException
    {
        try
        {
            return upload(baseDir, file, MimeTypeUtils.DEFAULT_ALLOWED_EXTENSION);
        }
        catch (Exception e)
        {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * 完整的文件上传方法
     * 执行文件上传的完整流程：文件校验 → 生成文件名 → 创建目录 → 保存文件 → 返回路径
     *
     * @param baseDir 相对应用的基目录路径
     * @param file 上传的MultipartFile对象
     * @param allowedExtension 允许上传的文件扩展名数组
     * @return 返回上传成功后的相对文件访问路径
     * @throws FileSizeLimitExceededException 文件大小超出限制
     * @throws FileNameLengthLimitExceededException 文件名长度超出限制
     * @throws IOException 文件读写异常
     * @throws InvalidExtensionException 文件类型校验异常
     */
    public static final String upload(String baseDir, MultipartFile file, String[] allowedExtension)
            throws FileSizeLimitExceededException, IOException, FileNameLengthLimitExceededException,
            InvalidExtensionException
    {
        // 检查文件名长度是否超出限制
        int fileNamelength = Objects.requireNonNull(file.getOriginalFilename()).length();
        if (fileNamelength > FileUploadUtils.DEFAULT_FILE_NAME_LENGTH)
        {
            throw new FileNameLengthLimitExceededException(FileUploadUtils.DEFAULT_FILE_NAME_LENGTH);
        }

        // 校验文件大小和类型
        assertAllowed(file, allowedExtension);

        // 生成唯一文件名
        String fileName = extractFilename(file);

        // 获取绝对路径并确保目录存在
        String absPath = getAbsoluteFile(baseDir, fileName).getAbsolutePath();

        // 将文件内容写入磁盘
        file.transferTo(Paths.get(absPath));

        // 返回相对访问路径
        return getPathFileName(baseDir, fileName);
    }

    /**
     * 生成唯一的文件名
     * 格式：日期路径/原文件名_序列号.扩展名
     * 例如：2024/12/20/avatar_1634567890123.jpg
     *
     * @param file 上传的文件对象
     * @return 生成的唯一文件名
     */
    public static final String extractFilename(MultipartFile file)
    {
        return StringUtils.format("{}/{}_{}.{}", DateUtils.datePath(),
                FilenameUtils.getBaseName(file.getOriginalFilename()), Seq.getId(Seq.uploadSeqType), getExtension(file));
    }

    /**
     * 获取文件的绝对路径File对象
     * 如果目录不存在，会自动创建所有必要的父目录
     *
     * @param uploadDir 上传目录路径
     * @param fileName 文件名
     * @return 文件绝对路径的File对象
     * @throws IOException 文件IO异常
     */
    public static final File getAbsoluteFile(String uploadDir, String fileName) throws IOException
    {
        File desc = new File(uploadDir + File.separator + fileName);

        // 如果目标文件不存在，检查并创建父目录
        if (!desc.exists())
        {
            if (!desc.getParentFile().exists())
            {
                // 递归创建所有必要的父目录
                desc.getParentFile().mkdirs();
            }
        }
        return desc;
    }

    /**
     * 获取文件的相对访问路径
     * 去除绝对路径中的基础路径部分，返回可供前端访问的相对URL
     *
     * @param uploadDir 上传目录路径
     * @param fileName 文件名
     * @return 相对访问路径，格式如：/profile/2024/12/20/filename.ext
     * @throws IOException 文件IO异常
     */
    public static final String getPathFileName(String uploadDir, String fileName) throws IOException
    {
        // 计算基础路径的长度，去掉profile部分
        int dirLastIndex = ProductConfig.getProfile().length() + 1;
        String currentDir = StringUtils.substring(uploadDir, dirLastIndex);
        return Constants.RESOURCE_PREFIX + "/" + currentDir + "/" + fileName;
    }

    /**
     * 文件大小校验
     *
     * @param file 上传的文件
     * @return
     * @throws FileSizeLimitExceededException 如果超出最大大小
     * @throws InvalidExtensionException
     */
    public static final void assertAllowed(MultipartFile file, String[] allowedExtension)
            throws FileSizeLimitExceededException, InvalidExtensionException
    {
        long size = file.getSize();
        if (size > DEFAULT_MAX_SIZE)
        {
            throw new FileSizeLimitExceededException(DEFAULT_MAX_SIZE / 1024 / 1024);
        }

        String fileName = file.getOriginalFilename();
        String extension = getExtension(file);
        if (allowedExtension != null && !isAllowedExtension(extension, allowedExtension))
        {
            if (allowedExtension == MimeTypeUtils.IMAGE_EXTENSION)
            {
                throw new InvalidExtensionException.InvalidImageExtensionException(allowedExtension, extension,
                        fileName);
            }
            else if (allowedExtension == MimeTypeUtils.FLASH_EXTENSION)
            {
                throw new InvalidExtensionException.InvalidFlashExtensionException(allowedExtension, extension,
                        fileName);
            }
            else if (allowedExtension == MimeTypeUtils.MEDIA_EXTENSION)
            {
                throw new InvalidExtensionException.InvalidMediaExtensionException(allowedExtension, extension,
                        fileName);
            }
            else if (allowedExtension == MimeTypeUtils.VIDEO_EXTENSION)
            {
                throw new InvalidExtensionException.InvalidVideoExtensionException(allowedExtension, extension,
                        fileName);
            }
            else
            {
                throw new InvalidExtensionException(allowedExtension, extension, fileName);
            }
        }
    }

    /**
     * 判断MIME类型是否是允许的MIME类型
     *
     * @param extension
     * @param allowedExtension
     * @return
     */
    public static final boolean isAllowedExtension(String extension, String[] allowedExtension)
    {
        for (String str : allowedExtension)
        {
            if (str.equalsIgnoreCase(extension))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取文件名的后缀
     *
     * @param file 表单文件
     * @return 后缀名
     */
    public static final String getExtension(MultipartFile file)
    {
        String extension = FilenameUtils.getExtension(file.getOriginalFilename());
        if (StringUtils.isEmpty(extension))
        {
            extension = MimeTypeUtils.getExtension(Objects.requireNonNull(file.getContentType()));
        }
        return extension;
    }
}
