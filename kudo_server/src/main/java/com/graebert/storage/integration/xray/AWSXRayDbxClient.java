package com.graebert.storage.integration.xray;

import com.amazonaws.xray.entities.Entity;
import com.dropbox.core.DbxDownloader;
import com.dropbox.core.DbxException;
import com.dropbox.core.RateLimitException;
import com.dropbox.core.v2.DbxClientV2;
import com.dropbox.core.v2.async.LaunchResultBase;
import com.dropbox.core.v2.files.CreateFolderResult;
import com.dropbox.core.v2.files.DbxUserFilesRequests;
import com.dropbox.core.v2.files.DbxUserListFolderBuilder;
import com.dropbox.core.v2.files.DeleteArg;
import com.dropbox.core.v2.files.DeleteBatchLaunch;
import com.dropbox.core.v2.files.DeleteResult;
import com.dropbox.core.v2.files.FileMetadata;
import com.dropbox.core.v2.files.GetTemporaryLinkResult;
import com.dropbox.core.v2.files.ListFolderResult;
import com.dropbox.core.v2.files.ListRevisionsMode;
import com.dropbox.core.v2.files.ListRevisionsResult;
import com.dropbox.core.v2.files.Metadata;
import com.dropbox.core.v2.files.RelocationResult;
import com.dropbox.core.v2.sharing.AddMember;
import com.dropbox.core.v2.sharing.DbxUserSharingRequests;
import com.dropbox.core.v2.sharing.FileMemberActionResult;
import com.dropbox.core.v2.sharing.FileMemberRemoveActionResult;
import com.dropbox.core.v2.sharing.ListFilesResult;
import com.dropbox.core.v2.sharing.ListFoldersResult;
import com.dropbox.core.v2.sharing.MemberSelector;
import com.dropbox.core.v2.sharing.RemoveMemberJobStatus;
import com.dropbox.core.v2.sharing.ShareFolderJobStatus;
import com.dropbox.core.v2.sharing.ShareFolderLaunch;
import com.dropbox.core.v2.sharing.SharedFileMembers;
import com.dropbox.core.v2.sharing.SharedFileMetadata;
import com.dropbox.core.v2.sharing.SharedFolderMembers;
import com.dropbox.core.v2.sharing.SharedFolderMetadata;
import com.dropbox.core.v2.sharing.SharedLinkMetadata;
import com.dropbox.core.v2.users.BasicAccount;
import com.dropbox.core.v2.users.DbxUserUsersRequests;
import com.dropbox.core.v2.users.FullAccount;
import com.graebert.storage.xray.OperationGroup;
import com.graebert.storage.xray.XRayEntityUtils;
import com.graebert.storage.xray.XRayManager;
import com.graebert.storage.xray.XrayField;
import java.util.List;

/**
 * Created by maria.baboshina on 07-Jun-17.
 */
public class AWSXRayDbxClient {
  private final DbxClientV2 dbxClientV2;
  private static final int MAX_RETRIES = 5;

  public AWSXRayDbxClient(DbxClientV2 dbxClientV2) {
    this.dbxClientV2 = dbxClientV2;
  }

  public AWSXRayDbxUserFilesRequests files() {
    return new AWSXRayDbxUserFilesRequests(dbxClientV2.files());
  }

  public AWSXRayDbxUserSharingRequests sharing() {
    return new AWSXRayDbxUserSharingRequests(dbxClientV2.sharing());
  }

  public AWSXRayDbxUserUsersRequests users() {
    return new AWSXRayDbxUserUsersRequests(dbxClientV2.users());
  }

  public static class AWSXRayDbxUserUsersRequests {
    DbxUserUsersRequests users;

    AWSXRayDbxUserUsersRequests(DbxUserUsersRequests users) {
      this.users = users;
    }

    public FullAccount getCurrentAccount() throws DbxException {
      Entity subsegment =
          XRayManager.createSubSegment(OperationGroup.DROPBOX, "Dropbox.Users.getCurrentAccount");

      FullAccount result = users.getCurrentAccount();
      XRayManager.endSegment(subsegment);
      return result;
    }

    public BasicAccount getAccount(String id) throws DbxException {
      Entity subsegment =
          XRayManager.createSubSegment(OperationGroup.DROPBOX, "Dropbox.Users.getAccount");

      BasicAccount result = users.getAccount(id);
      XRayManager.endSegment(subsegment);
      return result;
    }
  }

  public static class AWSXRayDbxUserSharingRequests {
    DbxUserSharingRequests sharing;

    AWSXRayDbxUserSharingRequests(DbxUserSharingRequests sharing) {
      this.sharing = sharing;
    }

    public DbxDownloader<SharedLinkMetadata> getSharedLinkFile(String url) throws DbxException {
      Entity subsegment =
          XRayManager.createSubSegment(OperationGroup.DROPBOX, "Dropbox.Sharing.getSharedLinkFile");

      try {
        DbxDownloader<SharedLinkMetadata> result = sharing.getSharedLinkFile(url);
        XRayManager.endSegment(subsegment);
        return result;
      } catch (DbxException e) {
        XRayManager.endSegment(subsegment, e);
        throw e;
      }
    }

    public SharedFileMetadata getFileMetadata(String path) throws DbxException {
      Entity subsegment =
          XRayManager.createSubSegment(OperationGroup.DROPBOX, "Dropbox.Sharing.getFileMetadata");
      XRayEntityUtils.putAnnotation(subsegment, XrayField.PATH, path);

      try {
        SharedFileMetadata result = sharing.getFileMetadata(path);
        XRayManager.endSegment(subsegment);
        return result;
      } catch (DbxException e) {
        XRayManager.endSegment(subsegment, e);
        throw e;
      }
    }

    public SharedFolderMetadata getFolderMetadata(String path) throws DbxException {
      Entity subsegment =
          XRayManager.createSubSegment(OperationGroup.DROPBOX, "Dropbox.Sharing.getFolderMetadata");
      XRayEntityUtils.putAnnotation(subsegment, XrayField.PATH, path);

      try {
        SharedFolderMetadata result = sharing.getFolderMetadata(path);
        XRayManager.endSegment(subsegment);
        return result;
      } catch (DbxException e) {
        XRayManager.endSegment(subsegment, e);
        throw e;
      }
    }

    public ListFoldersResult listFolders() throws DbxException {
      Entity subsegment =
          XRayManager.createSubSegment(OperationGroup.DROPBOX, "Dropbox.Sharing.listFolders");

      try {
        ListFoldersResult result = sharing.listFolders();
        XRayManager.endSegment(subsegment);
        return result;
      } catch (DbxException e) {
        XRayManager.endSegment(subsegment, e);
        throw e;
      }
    }

    public ListFoldersResult listFoldersContinue(String cursor) throws DbxException {
      Entity subsegment = XRayManager.createSubSegment(
          OperationGroup.DROPBOX, "Dropbox.Sharing.listFoldersContinue");

      try {
        ListFoldersResult result = sharing.listFoldersContinue(cursor);
        XRayManager.endSegment(subsegment);
        return result;
      } catch (DbxException e) {
        XRayManager.endSegment(subsegment, e);
        throw e;
      }
    }

    public ListFilesResult listReceivedFiles() throws DbxException {
      Entity subsegment =
          XRayManager.createSubSegment(OperationGroup.DROPBOX, "Dropbox.Sharing.listReceivedFiles");

      try {
        ListFilesResult result = sharing.listReceivedFiles();
        XRayManager.endSegment(subsegment);
        return result;
      } catch (DbxException e) {
        XRayManager.endSegment(subsegment, e);
        throw e;
      }
    }

    public ListFilesResult listReceivedFilesContinue(String cursor) throws DbxException {
      Entity subsegment = XRayManager.createSubSegment(
          OperationGroup.DROPBOX, "Dropbox.Sharing.listReceivedFilesContinue");

      try {
        ListFilesResult result = sharing.listReceivedFilesContinue(cursor);
        XRayManager.endSegment(subsegment);
        return result;
      } catch (DbxException e) {
        XRayManager.endSegment(subsegment, e);
        throw e;
      }
    }

    public ShareFolderJobStatus checkShareJobStatus(String asynchJobId) throws DbxException {
      Entity subsegment = XRayManager.createSubSegment(
          OperationGroup.DROPBOX, "Dropbox.Sharing.checkRemoveMemberJobStatus");

      try {
        ShareFolderJobStatus result = sharing.checkShareJobStatus(asynchJobId);
        XRayManager.endSegment(subsegment);
        return result;
      } catch (DbxException e) {
        XRayManager.endSegment(subsegment, e);
        throw e;
      }
    }

    public RemoveMemberJobStatus checkRemoveMemberJobStatus(String asynchJobId)
        throws DbxException {
      Entity subsegment = XRayManager.createSubSegment(
          OperationGroup.DROPBOX, "Dropbox.Sharing.checkRemoveMemberJobStatus");

      try {
        RemoveMemberJobStatus result = sharing.checkRemoveMemberJobStatus(asynchJobId);
        XRayManager.endSegment(subsegment);
        return result;
      } catch (DbxException e) {
        XRayManager.endSegment(subsegment, e);
        throw e;
      }
    }

    public SharedFileMembers listFileMembers(String path) throws DbxException {
      Entity subsegment =
          XRayManager.createSubSegment(OperationGroup.DROPBOX, "Dropbox.Sharing.listFileMembers");
      XRayEntityUtils.putAnnotation(subsegment, XrayField.PATH, path);

      try {
        SharedFileMembers result = sharing.listFileMembers(path);
        XRayManager.endSegment(subsegment);
        return result;
      } catch (DbxException e) {
        XRayManager.endSegment(subsegment, e);
        throw e;
      }
    }

    public LaunchResultBase removeFolderMember(String sharedFolderId, MemberSelector memberSelector)
        throws DbxException {
      Entity subsegment = XRayManager.createSubSegment(
          OperationGroup.DROPBOX, "Dropbox.Sharing.removeFolderMember");

      try {
        LaunchResultBase result = sharing.removeFolderMember(sharedFolderId, memberSelector, false);
        XRayManager.endSegment(subsegment);
        return result;
      } catch (DbxException e) {
        XRayManager.endSegment(subsegment, e);
        throw e;
      }
    }

    public FileMemberRemoveActionResult removeFileMember2(
        String path, MemberSelector memberSelector) throws DbxException {
      Entity subsegment =
          XRayManager.createSubSegment(OperationGroup.DROPBOX, "Dropbox.Sharing.removeFileMember2");
      XRayEntityUtils.putAnnotation(subsegment, XrayField.PATH, path);

      try {
        FileMemberRemoveActionResult result = sharing.removeFileMember2(path, memberSelector);
        XRayManager.endSegment(subsegment);
        return result;
      } catch (DbxException e) {
        XRayManager.endSegment(subsegment, e);
        throw e;
      }
    }

    public ShareFolderLaunch shareFolder(String path) throws DbxException {
      Entity subsegment =
          XRayManager.createSubSegment(OperationGroup.DROPBOX, "Dropbox.Sharing.shareFolder");
      XRayEntityUtils.putAnnotation(subsegment, XrayField.PATH, path);

      try {
        ShareFolderLaunch result = sharing.shareFolder(path);
        XRayManager.endSegment(subsegment);
        return result;
      } catch (DbxException e) {
        XRayManager.endSegment(subsegment, e);
        throw e;
      }
    }

    public void addFolderMember(String path, List<AddMember> members) throws DbxException {
      Entity subsegment =
          XRayManager.createSubSegment(OperationGroup.DROPBOX, "Dropbox.Sharing.addFolderMember");
      XRayEntityUtils.putAnnotation(subsegment, XrayField.PATH, path);

      try {
        sharing.addFolderMember(path, members);
        XRayManager.endSegment(subsegment);
      } catch (DbxException e) {
        XRayManager.endSegment(subsegment, e);
        throw e;
      }
    }

    public List<FileMemberActionResult> addFileMember(String path, List<MemberSelector> members)
        throws DbxException {
      Entity subsegment =
          XRayManager.createSubSegment(OperationGroup.DROPBOX, "Dropbox.Sharing.addFolderMember");
      XRayEntityUtils.putAnnotation(subsegment, XrayField.PATH, path);

      try {
        List<FileMemberActionResult> result = sharing.addFileMember(path, members);
        XRayManager.endSegment(subsegment);
        return result;
      } catch (DbxException e) {
        XRayManager.endSegment(subsegment, e);
        throw e;
      }
    }

    public SharedFolderMembers listFolderMembers(String sharedFolderId) throws DbxException {
      Entity subsegment =
          XRayManager.createSubSegment(OperationGroup.DROPBOX, "Dropbox.Sharing.addFolderMember");

      try {
        SharedFolderMembers result = sharing.listFolderMembers(sharedFolderId);
        XRayManager.endSegment(subsegment);
        return result;
      } catch (DbxException e) {
        XRayManager.endSegment(subsegment, e);
        throw e;
      }
    }
  }

  public static class AWSXRayDbxUserFilesRequests {
    DbxUserFilesRequests files;

    AWSXRayDbxUserFilesRequests(DbxUserFilesRequests files) {
      this.files = files;
    }

    public Metadata getMetadata(String path) throws DbxException {
      Entity subsegment =
          XRayManager.createSubSegment(OperationGroup.DROPBOX, "Dropbox.Files.getMetadata");
      XRayEntityUtils.putAnnotation(subsegment, XrayField.PATH, path);

      try {
        Metadata result = files.getMetadata(path);
        XRayManager.endSegment(subsegment);
        return result;
      } catch (DbxException e) {
        XRayManager.endSegment(subsegment, e);
        throw e;
      }
    }

    public String getDownloadUrl(String path) throws DbxException {
      Entity subsegment =
          XRayManager.createSubSegment(OperationGroup.DROPBOX, "Dropbox.Files.getDownloadUrl");
      XRayEntityUtils.putAnnotation(subsegment, XrayField.PATH, path);

      try {
        GetTemporaryLinkResult result = files.getTemporaryLink(path);
        XRayManager.endSegment(subsegment);
        return result.getLink();
      } catch (DbxException e) {
        XRayManager.endSegment(subsegment, e);
        throw e;
      }
    }

    public DbxDownloader<FileMetadata> download(String path) throws DbxException {
      Entity subsegment =
          XRayManager.createSubSegment(OperationGroup.DROPBOX, "Dropbox.Files.download");
      XRayEntityUtils.putAnnotation(subsegment, XrayField.PATH, path);

      try {
        DbxDownloader<FileMetadata> result = files.download(path);
        XRayManager.endSegment(subsegment);
        return result;
      } catch (DbxException e) {
        XRayManager.endSegment(subsegment, e);
        throw e;
      }
    }

    public DbxDownloader<FileMetadata> download(String path, String versionId) throws DbxException {
      Entity subsegment =
          XRayManager.createSubSegment(OperationGroup.DROPBOX, "Dropbox.Files.download");
      XRayEntityUtils.putAnnotation(subsegment, XrayField.PATH, path);

      try {
        DbxDownloader<FileMetadata> result = files.download(path, versionId);
        XRayManager.endSegment(subsegment);
        return result;
      } catch (DbxException e) {
        XRayManager.endSegment(subsegment, e);
        throw e;
      }
    }

    public ListFolderResult listFolder(String path) throws DbxException {
      Entity subsegment =
          XRayManager.createSubSegment(OperationGroup.DROPBOX, "Dropbox.Files.listFolder");
      XRayEntityUtils.putAnnotation(subsegment, XrayField.PATH, path);

      try {
        ListFolderResult result = files.listFolder(path);
        XRayManager.endSegment(subsegment);
        return result;
      } catch (DbxException e) {
        XRayManager.endSegment(subsegment, e);
        throw e;
      }
    }

    public DbxUserListFolderBuilder listFolderBuilder(String path) throws DbxException {
      Entity subsegment =
          XRayManager.createSubSegment(OperationGroup.DROPBOX, "Dropbox.Files.listFolderBuilder");
      XRayEntityUtils.putAnnotation(subsegment, XrayField.PATH, path);

      try {
        DbxUserListFolderBuilder result = files.listFolderBuilder(path);
        XRayManager.endSegment(subsegment);
        return result;
      } catch (Exception e) {
        XRayManager.endSegment(subsegment, e);
        throw e;
      }
    }

    public ListFolderResult listFolderContinue(String cursor) throws DbxException {
      Entity subsegment =
          XRayManager.createSubSegment(OperationGroup.DROPBOX, "Dropbox.Files.listFolderContinue");

      try {
        ListFolderResult result = files.listFolderContinue(cursor);
        XRayManager.endSegment(subsegment);
        return result;
      } catch (DbxException e) {
        XRayManager.endSegment(subsegment, e);
        throw e;
      }
    }

    public void permanentlyDelete(String path) throws DbxException {
      Entity subsegment =
          XRayManager.createSubSegment(OperationGroup.DROPBOX, "Dropbox.Files.permanentlyDelete");
      XRayEntityUtils.putAnnotation(subsegment, XrayField.PATH, path);

      try {
        files.permanentlyDelete(path);
        XRayManager.endSegment(subsegment);
      } catch (DbxException e) {
        XRayManager.endSegment(subsegment, e);
        throw e;
      }
    }

    public RelocationResult copyV2(String path, String newPath) throws DbxException {
      Entity subsegment =
          XRayManager.createSubSegment(OperationGroup.DROPBOX, "Dropbox.Files.copyV2");
      XRayEntityUtils.putAnnotation(subsegment, XrayField.PATH, path);

      try {
        RelocationResult result = files.copyV2(path, newPath);
        XRayManager.endSegment(subsegment);
        return result;
      } catch (DbxException e) {
        XRayManager.endSegment(subsegment, e);
        throw e;
      }
    }

    public FileMetadata restore(String path, String rev) throws DbxException {
      Entity subsegment =
          XRayManager.createSubSegment(OperationGroup.DROPBOX, "Dropbox.Files.restore");
      XRayEntityUtils.putAnnotation(subsegment, XrayField.PATH, path);

      try {
        FileMetadata result = files.restore(path, rev);
        XRayManager.endSegment(subsegment);
        return result;
      } catch (DbxException e) {
        XRayManager.endSegment(subsegment, e);
        throw e;
      }
    }

    public ListRevisionsResult listRevisions(String path) throws DbxException {
      Entity subsegment =
          XRayManager.createSubSegment(OperationGroup.DROPBOX, "Dropbox.Files.listRevisions");
      XRayEntityUtils.putAnnotation(subsegment, XrayField.PATH, path);

      try {
        ListRevisionsResult result = files
            .listRevisionsBuilder(path)
            .withMode(ListRevisionsMode.ID)
            .withLimit(100L)
            .start();
        XRayManager.endSegment(subsegment);
        return result;
      } catch (DbxException e) {
        XRayManager.endSegment(subsegment, e);
        throw e;
      }
    }

    public DeleteBatchLaunch deleteBatch(List<DeleteArg> list) throws DbxException {
      Entity subsegment =
          XRayManager.createSubSegment(OperationGroup.DROPBOX, "Dropbox.Files.deleteBatch");

      try {
        DeleteBatchLaunch result = files.deleteBatch(list);
        XRayManager.endSegment(subsegment);
        return result;
      } catch (DbxException e) {
        XRayManager.endSegment(subsegment, e);
        throw e;
      }
    }

    public DeleteResult delete(String path) throws DbxException {
      Entity subsegment =
          XRayManager.createSubSegment(OperationGroup.DROPBOX, "Dropbox.Files.deleteV2");
      XRayEntityUtils.putAnnotation(subsegment, XrayField.PATH, path);

      try {
        DeleteResult result = files.deleteV2(path);
        XRayManager.endSegment(subsegment);
        return result;
      } catch (DbxException e) {
        XRayManager.endSegment(subsegment, e);
        throw e;
      }
    }

    public RelocationResult moveV2(String from, String to) throws DbxException {
      Entity subsegment =
          XRayManager.createSubSegment(OperationGroup.DROPBOX, "Dropbox.Files.moveV2");

      try {
        RelocationResult result = files.moveV2(from, to);
        XRayManager.endSegment(subsegment);
        return result;
      } catch (DbxException e) {
        XRayManager.endSegment(subsegment, e);
        throw e;
      }
    }

    public CreateFolderResult createFolderV2(String path, int attempt)
        throws DbxException, InterruptedException {
      Entity subsegment =
          XRayManager.createSubSegment(OperationGroup.DROPBOX, "Dropbox.Files.createFolderV2");
      XRayEntityUtils.putAnnotation(subsegment, XrayField.PATH, path);

      try {
        CreateFolderResult result = files.createFolderV2(path);
        XRayManager.endSegment(subsegment);
        return result;
      } catch (DbxException e) {
        XRayManager.endSegment(subsegment, e);
        if (e instanceof RateLimitException && attempt < MAX_RETRIES) {
          Thread.sleep(100);
          return createFolderV2(path, attempt + 1);
        }
        throw e;
      }
    }
  }
}
