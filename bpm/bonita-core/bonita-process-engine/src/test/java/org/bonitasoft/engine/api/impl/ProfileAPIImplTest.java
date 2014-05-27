package org.bonitasoft.engine.api.impl;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.bonitasoft.engine.api.impl.transaction.profile.CreateProfileMember;
import org.bonitasoft.engine.identity.IdentityService;
import org.bonitasoft.engine.identity.MemberType;
import org.bonitasoft.engine.identity.model.SGroup;
import org.bonitasoft.engine.identity.model.SRole;
import org.bonitasoft.engine.identity.model.SUser;
import org.bonitasoft.engine.profile.ProfileMember;
import org.bonitasoft.engine.profile.ProfileService;
import org.bonitasoft.engine.profile.model.SProfileMember;
import org.bonitasoft.engine.service.PlatformServiceAccessor;
import org.bonitasoft.engine.service.TenantServiceAccessor;
import org.bonitasoft.engine.session.SessionService;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.runners.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class ProfileAPIImplTest {

    @Spy
    private ProfileAPIImpl profileAPIImpl;

    @Mock
    TenantServiceAccessor tenantServiceAccessor;

    @Mock
    ProfileService profileService;

    @Mock
    SessionService sessionService;

    @Mock
    private IdentityService identityService;

    @Mock
    SProfileMember sProfileMember;

    @Mock
    PlatformServiceAccessor platformServiceAccessor;

    @Mock
    private SGroup sGroup;

    @Mock
    private SUser sUser;

    @Mock
    private SRole sRole;

    @Mock
    private ProfileMember profileMember;

    @Before
    public void before() throws Exception {
        doReturn(sGroup).when(identityService).getGroup(anyLong());
        doReturn(sUser).when(identityService).getUser(anyLong());
        doReturn(sRole).when(identityService).getRole(anyLong());

        doReturn("group").when(sGroup).getName();
        doReturn("/parent").when(sGroup).getParentPath();

        doReturn("role").when(sRole).getName();

        doReturn(tenantServiceAccessor).when(profileAPIImpl).getTenantAccessor();
        doReturn(identityService).when(tenantServiceAccessor).getIdentityService();

        doReturn(1l).when(profileAPIImpl).getUserIdFromSession();
        doReturn(profileMember).when(profileAPIImpl).convertToProfileMember(any(CreateProfileMember.class));

        doReturn(profileService).when(tenantServiceAccessor).getProfileService();

        doReturn(sProfileMember).when(profileService).getProfileMemberWithoutDisplayName(anyLong());
    }

    @Test
    public void should_deleteProfileMember_update_profilemetadata() throws Exception {
        // when
        profileAPIImpl.deleteProfileMember(1L);

        // then
        verify(profileService, times(1)).updateProfileMetaData(anyLong(), anyLong());
    }

    @Test
    public void should_updateProfileMetaData_update_profilemetadata() throws Exception {
        final Long profileId = 1L;
        final Long userId = 2L;
        final Long groupId = 3L;
        final Long roleId = 4L;

        doNothing().when(profileAPIImpl).checkIfProfileMemberExists(any(TenantServiceAccessor.class), any(ProfileService.class), any(Long.class),
                any(Long.class), any(Long.class), any(Long.class),
                any(MemberType.class));

        // when
        profileAPIImpl.createProfileMember(profileId, userId, groupId, roleId);

        // then
        verify(profileService, times(1)).updateProfileMetaData(anyLong(), anyLong());

    }
}
