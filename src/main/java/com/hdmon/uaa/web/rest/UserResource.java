package com.hdmon.uaa.web.rest;

import com.hdmon.uaa.config.Constants;
import com.codahale.metrics.annotation.Timed;
import com.hdmon.uaa.domain.IsoResponseEntity;
import com.hdmon.uaa.domain.User;
import com.hdmon.uaa.repository.UserRepository;
import com.hdmon.uaa.security.AuthoritiesConstants;
import com.hdmon.uaa.service.MailService;
import com.hdmon.uaa.service.UserService;
import com.hdmon.uaa.service.dto.UserDTO;
import com.hdmon.uaa.service.util.MicroserviceHelper;
import com.hdmon.uaa.web.rest.errors.BadRequestAlertException;
import com.hdmon.uaa.web.rest.errors.EmailAlreadyUsedException;
import com.hdmon.uaa.web.rest.errors.LoginAlreadyUsedException;
import com.hdmon.uaa.web.rest.errors.ResponseErrorCode;
import com.hdmon.uaa.web.rest.util.HeaderUtil;
import com.hdmon.uaa.web.rest.util.PaginationUtil;
import io.github.jhipster.web.util.ResponseUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.annotation.Secured;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;

import static org.hibernate.id.IdentifierGenerator.ENTITY_NAME;

/**
 * REST controller for managing users.
 * <p>
 * This class accesses the User entity, and needs to fetch its collection of authorities.
 * <p>
 * For a normal use-case, it would be better to have an eager relationship between User and Authority,
 * and send everything to the client side: there would be no View Model and DTO, a lot less code, and an outer-join
 * which would be good for performance.
 * <p>
 * We use a View Model and a DTO for 3 reasons:
 * <ul>
 * <li>We want to keep a lazy association between the user and the authorities, because people will
 * quite often do relationships with the user, and we don't want them to get the authorities all
 * the time for nothing (for performance reasons). This is the #1 goal: we should not impact our users'
 * application because of this use-case.</li>
 * <li> Not having an outer join causes n+1 requests to the database. This is not a real issue as
 * we have by default a second-level cache. This means on the first HTTP call we do the n+1 requests,
 * but then all authorities come from the cache, so in fact it's much better than doing an outer join
 * (which will get lots of data from the database, for each HTTP call).</li>
 * <li> As this manages users, for security reasons, we'd rather have a DTO layer.</li>
 * </ul>
 * <p>
 * Another option would be to have a specific JPA entity graph to handle this case.
 */
@RestController
@RequestMapping("/api")
public class UserResource {

    private final Logger log = LoggerFactory.getLogger(UserResource.class);

    private final UserRepository userRepository;

    private final UserService userService;

    private final MailService mailService;

    public UserResource(UserRepository userRepository, UserService userService, MailService mailService) {

        this.userRepository = userRepository;
        this.userService = userService;
        this.mailService = mailService;
    }

    /**
     * POST  /users  : Creates a new user.
     * <p>
     * Creates a new user if the login and email are not already used, and sends an
     * mail with an activation link.
     * The user needs to be activated on creation.
     *
     * @param userDTO the user to create
     * @return the ResponseEntity with status 201 (Created) and with body the new user, or with status 400 (Bad Request) if the login or email is already in use
     * @throws URISyntaxException if the Location URI syntax is incorrect
     * @throws BadRequestAlertException 400 (Bad Request) if the login or email is already in use
     */
    @PostMapping("/users")
    @Timed
    @Secured(AuthoritiesConstants.ADMIN)
    public ResponseEntity<User> createUser(@Valid @RequestBody UserDTO userDTO) throws URISyntaxException {
        log.debug("REST request to save User : {}", userDTO);

        if (userDTO.getId() != null) {
            throw new BadRequestAlertException("A new user cannot already have an ID", "userManagement", "idexists");
            // Lowercase the user login before comparing with database
        } else if (userRepository.findOneByLogin(userDTO.getLogin().toLowerCase()).isPresent()) {
            throw new LoginAlreadyUsedException();
        } else if (userRepository.findOneByEmailIgnoreCase(userDTO.getEmail()).isPresent()) {
            throw new EmailAlreadyUsedException();
        } else {
            User newUser = userService.createUser(userDTO);
            mailService.sendCreationEmail(newUser);
            return ResponseEntity.created(new URI("/api/users/" + newUser.getLogin()))
                .headers(HeaderUtil.createAlert( "userManagement.created", newUser.getLogin()))
                .body(newUser);
        }
    }

    /**
     * PUT /users : Updates an existing User.
     *
     * @param userDTO the user to update
     * @return the ResponseEntity with status 200 (OK) and with body the updated user
     * @throws EmailAlreadyUsedException 400 (Bad Request) if the email is already in use
     * @throws LoginAlreadyUsedException 400 (Bad Request) if the login is already in use
     */
    @PutMapping("/users")
    @Timed
    @Secured(AuthoritiesConstants.ADMIN)
    public ResponseEntity<UserDTO> updateUser(@Valid @RequestBody UserDTO userDTO) {
        log.debug("REST request to update User : {}", userDTO);
        Optional<User> existingUser = userRepository.findOneByEmailIgnoreCase(userDTO.getEmail());
        if (existingUser.isPresent() && (!existingUser.get().getId().equals(userDTO.getId()))) {
            throw new EmailAlreadyUsedException();
        }
        existingUser = userRepository.findOneByLogin(userDTO.getLogin().toLowerCase());
        if (existingUser.isPresent() && (!existingUser.get().getId().equals(userDTO.getId()))) {
            throw new LoginAlreadyUsedException();
        }
        Optional<UserDTO> updatedUser = userService.updateUser(userDTO);

        return ResponseUtil.wrapOrNotFound(updatedUser,
            HeaderUtil.createAlert("userManagement.updated", userDTO.getLogin()));
    }

    /**
     * GET /users : get all users.
     *
     * @param pageable the pagination information
     * @return the ResponseEntity with status 200 (OK) and with body all users
     */
    @GetMapping("/users")
    @Timed
    public ResponseEntity<List<UserDTO>> getAllUsers(Pageable pageable) {
        final Page<UserDTO> page = userService.getAllManagedUsers(pageable);
        HttpHeaders headers = PaginationUtil.generatePaginationHttpHeaders(page, "/api/users");
        return new ResponseEntity<>(page.getContent(), headers, HttpStatus.OK);
    }

    /**
     * @return a string list of the all of the roles
     */
    @GetMapping("/users/authorities")
    @Timed
    @Secured(AuthoritiesConstants.ADMIN)
    public List<String> getAuthorities() {
        return userService.getAuthorities();
    }

    /**
     * GET /users/:login : get the "login" user.
     *
     * @param login the login of the user to find
     * @return the ResponseEntity with status 200 (OK) and with body the "login" user, or with status 404 (Not Found)
     */
    @GetMapping("/users/{login:" + Constants.LOGIN_REGEX + "}")
    @Timed
    public ResponseEntity<UserDTO> getUser(@PathVariable String login) {
        log.debug("REST request to get User : {}", login);
        return ResponseUtil.wrapOrNotFound(
            userService.getUserWithAuthoritiesByLogin(login)
                .map(UserDTO::new));
    }

    /**
     * DELETE /users/:login : delete the "login" User.
     *
     * @param login the login of the user to delete
     * @return the ResponseEntity with status 200 (OK)
     */
    @DeleteMapping("/users/{login:" + Constants.LOGIN_REGEX + "}")
    @Timed
    @Secured(AuthoritiesConstants.ADMIN)
    public ResponseEntity<Void> deleteUser(@PathVariable String login) {
        log.debug("REST request to delete User: {}", login);
        userService.deleteUser(login);
        return ResponseEntity.ok().headers(HeaderUtil.createAlert( "userManagement.deleted", login)).build();
    }

    //=========================================HDMON-START=========================================

    /**
     * GET  /hd/logout : đăng xuất khỏi hệ thống.
     * Last update date: 20-07-2018
     * @return cấu trúc json, báo kết quả dữ liệu lấy được.
     */
    @GetMapping("/hd/users/getinfobymobile/{mobile}")
    @Timed
    public ResponseEntity<IsoResponseEntity> getUserByMobile_hd(@PathVariable String mobile) {
        log.debug("REST request to get User : {}", mobile);

        IsoResponseEntity<User> responseEntity = new IsoResponseEntity<>();
        HttpHeaders httpHeaders;

        try {
            if(!mobile.isEmpty()) {
                Optional<User> dbResults = userService.getUserInfoByMobile_hd(mobile);
                User resData = (dbResults != null && dbResults.isPresent()) ? dbResults.get() : null;
                if(resData != null)
                    resData.setEmail(getRealEmail(resData));

                responseEntity.setError(ResponseErrorCode.SUCCESSFULL.getValue());
                responseEntity.setData(resData);
                responseEntity.setMessage("successfull");

                String urlRequest = String.format("/hd/users/getinfobymobile/%s", mobile);
                httpHeaders = HeaderUtil.createAlert(ENTITY_NAME, urlRequest);
            }
            else
            {
                responseEntity.setError(ResponseErrorCode.INVALIDDATA.getValue());
                responseEntity.setMessage("invalid");
                responseEntity.setException("The Mobile cannot not null!");
                httpHeaders = HeaderUtil.createFailureAlert(ENTITY_NAME, "invalid", "The Mobile cannot not null!");
            }
        }
        catch (Exception ex)
        {
            responseEntity.setError(ResponseErrorCode.SYSTEM_ERROR.getValue());
            responseEntity.setMessage("system_error");
            responseEntity.setException(ex.getMessage());

            httpHeaders = HeaderUtil.createFailureAlert(ENTITY_NAME, "system_error", ex.getMessage());
        }

        return new ResponseEntity<>(responseEntity, httpHeaders, HttpStatus.OK);
    }

    /**
     * GET /hd/users/getinfobyusername/{username} : lấy thông tin thành viên thông qua username.
     * Last update date: 20-07-2018
     * @return cấu trúc json, báo kết quả dữ liệu lấy được.
     */
    @GetMapping("/hd/users/getinfobyusername/{username:" + Constants.LOGIN_REGEX + "}")
    @Timed
    public ResponseEntity<IsoResponseEntity> getUserByUsername_hd(@PathVariable String username) {
        log.debug("REST request to get User : {}", username);

        IsoResponseEntity<User> responseEntity = new IsoResponseEntity<>();
        HttpHeaders httpHeaders;

        try {
            if(!username.isEmpty()) {
                Optional<User> dbResults = userService.getUserInfoByUsername_hd(username);
                User resData = (dbResults != null && dbResults.isPresent()) ? dbResults.get() : null;
                if(resData != null)
                    resData.setEmail(getRealEmail(resData));

                responseEntity.setError(ResponseErrorCode.SUCCESSFULL.getValue());
                responseEntity.setData(resData);
                responseEntity.setMessage("successfull");

                String urlRequest = String.format("/hd/users/getinfobyusername/%s", username);
                httpHeaders = HeaderUtil.createAlert(ENTITY_NAME, urlRequest);
            }
            else
            {
                responseEntity.setError(ResponseErrorCode.INVALIDDATA.getValue());
                responseEntity.setMessage("invalid");
                responseEntity.setException("The Username cannot not null!");
                httpHeaders = HeaderUtil.createFailureAlert(ENTITY_NAME, "invalid", "The Username cannot not null!");
            }
        }
        catch (Exception ex)
        {
            responseEntity.setError(ResponseErrorCode.SYSTEM_ERROR.getValue());
            responseEntity.setMessage("system_error");
            responseEntity.setException(ex.getMessage());

            httpHeaders = HeaderUtil.createFailureAlert(ENTITY_NAME, "system_error", ex.getMessage());
        }

        return new ResponseEntity<>(responseEntity, httpHeaders, HttpStatus.OK);
    }

    /**
     * GET  /hd/users/getinfobyuserid/{userId} : lấy thông tin thành viên thông qua userId.
     * Last update date: 20-07-2018
     * @return cấu trúc json, báo kết quả dữ liệu lấy được.
     */
    @GetMapping("/hd/users/getinfobyuserid/{userId}")
    @Timed
    public ResponseEntity<IsoResponseEntity> getUserByUserId_hd(@PathVariable Long userId) {
        log.debug("REST request to get User : {}", userId);

        IsoResponseEntity<User> responseEntity = new IsoResponseEntity<>();
        HttpHeaders httpHeaders;

        try {
            if(userId != null && userId > 0) {
                User dbResults = userService.getInfoByUserId_hd(userId);
                dbResults.setEmail(getRealEmail(dbResults));

                responseEntity.setError(ResponseErrorCode.SUCCESSFULL.getValue());
                responseEntity.setData(dbResults);
                responseEntity.setMessage("successfull");

                String urlRequest = String.format("/hd/users/getinfobyuserid/%s", userId);
                httpHeaders = HeaderUtil.createAlert(ENTITY_NAME, urlRequest);
            }
            else
            {
                responseEntity.setError(ResponseErrorCode.INVALIDDATA.getValue());
                responseEntity.setMessage("invalid");
                responseEntity.setException("The UserId cannot not null!");
                httpHeaders = HeaderUtil.createFailureAlert(ENTITY_NAME, "invalid", "The UserId cannot not null!");
            }
        }
        catch (Exception ex)
        {
            responseEntity.setError(ResponseErrorCode.SYSTEM_ERROR.getValue());
            responseEntity.setMessage("system_error");
            responseEntity.setException(ex.getMessage());

            httpHeaders = HeaderUtil.createFailureAlert(ENTITY_NAME, "system_error", ex.getMessage());
        }

        return new ResponseEntity<>(responseEntity, httpHeaders, HttpStatus.OK);
    }

    /**
     * GET /hd/users/checkexists/username/{username} : kiểm tra username đã tồn tại trong hệ thống chưa
     * không yêu cầu đăng nhập khi gọi.
     * Last update date: 20-07-2018
     * @return cấu trúc json, báo kết quả dữ liệu lấy được.
     */
    @GetMapping("/hd/users/checkexists/username/{username:" + Constants.LOGIN_REGEX + "}")
    @Timed
    public ResponseEntity<IsoResponseEntity> checkExistsByUsername_hd(@PathVariable String username) {
        log.debug("REST request to check exist User : {}", username);

        IsoResponseEntity responseEntity = new IsoResponseEntity();
        HttpHeaders httpHeaders;

        try {
            if(!username.isEmpty()) {
                boolean blResult = false;
                Optional<User> dbResults = userService.getUserInfoByUsername_hd(username);
                if(dbResults != null && dbResults.isPresent())
                {
                    blResult = (dbResults.get().getId() > 0) ? true : false;
                }

                responseEntity.setError(ResponseErrorCode.SUCCESSFULL.getValue());
                responseEntity.setData(blResult);
                responseEntity.setMessage("successfull");

                String urlRequest = String.format("/hd/users/checkexists/username/%s", username);
                httpHeaders = HeaderUtil.createAlert(ENTITY_NAME, urlRequest);
            }
            else
            {
                responseEntity.setError(ResponseErrorCode.INVALIDDATA.getValue());
                responseEntity.setMessage("invalid");
                responseEntity.setException("The Username cannot not null!");
                httpHeaders = HeaderUtil.createFailureAlert(ENTITY_NAME, "invalid", "The Username cannot not null!");
            }
        }
        catch (Exception ex)
        {
            responseEntity.setError(ResponseErrorCode.SYSTEM_ERROR.getValue());
            responseEntity.setMessage("system_error");
            responseEntity.setException(ex.getMessage());

            httpHeaders = HeaderUtil.createFailureAlert(ENTITY_NAME, "system_error", ex.getMessage());
        }

        return new ResponseEntity<>(responseEntity, httpHeaders, HttpStatus.OK);
    }

    /**
     * GET /hd/users/checkexists/mobile/{mobile} : kiểm tra mobile đã tồn tại trong hệ thống chưa
     * không yêu cầu đăng nhập khi gọi.
     * Last update date: 20-07-2018
     * @return cấu trúc json, báo kết quả dữ liệu lấy được.
     */
    @GetMapping("/hd/users/checkexists/mobile/{mobile}")
    @Timed
    public ResponseEntity<IsoResponseEntity> checkExistsByMobile_hd(HttpServletRequest request, HttpServletResponse response, @PathVariable String mobile) {
        log.debug("REST request to check exist User : {}", mobile);

        IsoResponseEntity responseEntity = new IsoResponseEntity();
        HttpHeaders httpHeaders;

        try {
            if(!mobile.isEmpty()) {
                Map<String, Object> resData = userService.execCheckExistsByMobile_hd(mobile);

                responseEntity.setError(ResponseErrorCode.SUCCESSFULL.getValue());
                responseEntity.setData(resData);
                responseEntity.setMessage("successfull");

                String urlRequest = String.format("/hd/users/checkexists/mobile/%s", mobile);
                httpHeaders = HeaderUtil.createAlert(ENTITY_NAME, urlRequest);
            }
            else
            {
                responseEntity.setError(ResponseErrorCode.INVALIDDATA.getValue());
                responseEntity.setMessage("invalid");
                responseEntity.setException("The Mobile cannot not null!");
                httpHeaders = HeaderUtil.createFailureAlert(ENTITY_NAME, "invalid", "The Mobile cannot not null!");
            }
        }
        catch (Exception ex)
        {
            responseEntity.setError(ResponseErrorCode.SYSTEM_ERROR.getValue());
            responseEntity.setMessage("system_error");
            responseEntity.setException(ex.getMessage());

            httpHeaders = HeaderUtil.createFailureAlert(ENTITY_NAME, "system_error", ex.getMessage());
        }

        return new ResponseEntity<>(responseEntity, httpHeaders, HttpStatus.OK);
    }

    /**
     * So sánh và lấy giá trị thực của email.
     * Last update date: 20-07-2018
     * @return trả về giá trị email thực sự của User.
     */
    private String getRealEmail(User dbResults)
    {
        String realEmail = dbResults.getEmail();
        if(dbResults != null && dbResults.getId() != null) {
            String tempEmail =  dbResults.getLogin() + ".no-email@hdmon.com";
            if(realEmail.equals(tempEmail))
                realEmail = "";
        }
        return realEmail;
    }
    //===========================================HDMON-END===========================================
}
